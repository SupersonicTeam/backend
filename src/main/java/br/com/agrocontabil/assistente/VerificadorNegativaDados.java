package br.com.agrocontabil.assistente;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Fim da negativa falsa na rota de dados (issue #274, item 6): resultado vazio nunca vira "não
 * tem" absoluto.
 *
 * <p>A negativa falsa era o pior erro da Ceres: "não tem débito pendente" com R$ 5.521,90 em guias
 * vencidas (#259), "não há notas devolvidas" filtrando as rejeitadas (#254). O dado existia; a
 * função consultada não cobria a pergunta, e o modelo transformou "a consulta voltou vazia" em "não
 * existe". O prompt pede a frase com o recorte, mas um modelo de 4B a temperatura 0,2 desobedece
 * de vez em quando, então a garantia fica aqui, no espírito do {@link VerificadorAfirmacaoDados}.
 *
 * <p>Regra: com alguma função que voltou vazia ({@link ContextoAssistente#fontesVazias()}), toda
 * frase com negativa absoluta ("não tem", "não há", "nenhum", "não possui", "não existe"...) que
 * não traz o recorte de uma consulta vazia (as datas, o mês ou o número consultados) sai, e a
 * resposta ganha a frase fixa de cada consulta vazia, com o período e o filtro reais. Mostrar o
 * filtro expõe a escolha errada do modelo: o produtor lê "Não encontrei notas rejeitadas pela
 * SEFAZ entre...", e não "não há notas devolvidas". O resto da resposta fica. Sem consulta vazia,
 * nada muda: a negativa sobre um resultado que veio cheio não é deste verificador.
 */
@Component
public class VerificadorNegativaDados {

  private static final Logger log = LoggerFactory.getLogger(VerificadorNegativaDados.class);

  /** Negativa absoluta, já sem acento e em minúsculas. */
  private static final Pattern NEGATIVA = Pattern.compile("\\b("
      + "nao (tem|tens|ha|houve|possui|possuem|existe|existem|consta|constam|ha registro|"
      + "foram encontrad\\w*|foi encontrad\\w*)|nenhum|nenhuma|inexistente|inexistentes)\\b");

  private static final Pattern FIM_DE_FRASE = Pattern.compile("(?<=[.!?])\\s+|\\n+");
  private static final Pattern DIACRITICOS = Pattern.compile("\\p{M}+");
  private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
  private static final DateTimeFormatter MES = DateTimeFormatter.ofPattern("MM/yyyy");

  /** Como cada situação de nota aparece na frase fixa (o filtro que o modelo escolheu). */
  private static final Map<String, String> SITUACAO = Map.ofEntries(
      Map.entry("RASCUNHO", "em rascunho"),
      Map.entry("COM_INCONSISTENCIAS", "com inconsistencias"),
      Map.entry("AGUARDANDO_APROVACAO", "aguardando aprovacao do contador"),
      Map.entry("APROVADA", "aprovadas pelo contador"),
      Map.entry("DEVOLVIDA", "devolvidas pelo contador"),
      Map.entry("XML_GERADO", "com XML gerado e nao enviadas"),
      Map.entry("ENVIANDO", "sendo enviadas a SEFAZ"),
      Map.entry("AUTORIZADA", "autorizadas"),
      Map.entry("REJEITADA", "rejeitadas pela SEFAZ"),
      Map.entry("PENDENTE_SEFAZ", "aguardando a SEFAZ"),
      Map.entry("CANCELANDO", "em cancelamento"),
      Map.entry("CANCELADA", "canceladas"));

  /**
   * A resposta com as negativas sem recorte trocadas, ou vazio se nada mudou.
   *
   * @param texto    resposta da rota de dados, já aceita pelo {@code ValidadorNumeros}
   * @param contexto contexto da pergunta (as consultas vazias e o "hoje")
   * @return o texto novo, se houve troca
   */
  public Optional<String> substituir(String texto, ContextoAssistente contexto) {
    List<FonteDado> vazias = contexto.fontesVazias();
    if (vazias.isEmpty() || texto == null || texto.isBlank()) {
      return Optional.empty();
    }
    List<String> recortes = new ArrayList<>();
    vazias.forEach(f -> recortes.addAll(recortes(f)));

    List<String> mantidas = new ArrayList<>();
    int trocadas = 0;
    for (String frase : FIM_DE_FRASE.split(texto.strip())) {
      String normalizada = normalizar(frase);
      boolean negativa = NEGATIVA.matcher(normalizada).find();
      boolean comRecorte = recortes.stream().anyMatch(frase::contains);
      if (negativa && !comRecorte) {
        trocadas++;
      } else if (!frase.isBlank()) {
        mantidas.add(frase.strip());
      }
    }
    if (trocadas == 0) {
      return Optional.empty();
    }
    Set<String> frasesFixas = new LinkedHashSet<>();
    vazias.forEach(f -> frasesFixas.add(fraseFixa(f, contexto.hoje())));
    // Só o tipo da troca vai ao log: o texto pode ter dado do produtor.
    log.info("Ceres: {} negativa(s) sem recorte trocada(s) pela frase do backend ({} consulta(s) "
        + "vazia(s))", trocadas, vazias.size());
    List<String> partes = new ArrayList<>(mantidas);
    partes.addAll(frasesFixas);
    return Optional.of(String.join(" ", partes));
  }

  /**
   * O que conta como recorte de uma consulta: as datas (como a função as formata e como o produtor
   * as escreve), o mês e o número da nota. "No período informado" não conta.
   */
  static List<String> recortes(FonteDado fonte) {
    List<String> recortes = new ArrayList<>();
    for (Map.Entry<String, String> p : fonte.parametros().entrySet()) {
      String valor = p.getValue();
      switch (p.getKey()) {
        case "dataInicio", "dataFim" -> data(valor).ifPresent(d -> recortes.add(DATA.format(d)));
        case "mes" -> mes(valor).ifPresent(m -> recortes.add(MES.format(m)));
        case "numero" -> recortes.add(valor);
        case "dias" -> recortes.add(valor + " dias");
        default -> { }
      }
    }
    return recortes;
  }

  /** Frase fixa de uma consulta vazia, com o recorte e o filtro reais. */
  static String fraseFixa(FonteDado fonte, LocalDate hoje) {
    Map<String, String> p = fonte.parametros();
    String periodo = periodo(p);
    return switch (fonte.funcao()) {
      case "listarNotas" -> "Nao encontrei notas" + tipo(p.get("tipo"))
          + situacao(p.get("situacao")) + periodo + ".";
      case "totalComprasPorInsumo" -> "Nao encontrei compras de " + p.get("descricaoOuNcm")
          + periodo + ".";
      case "totalVendasPorProduto" -> "Nao encontrei vendas autorizadas de "
          + p.get("descricaoOuNcm") + periodo + ".";
      case "consultarCreditos" -> "Nao encontrei compras com credito" + periodo + ".";
      case "consultarRetencoes" -> "Nao encontrei vendas autorizadas" + periodo + ".";
      case "consultarApuracao" -> "Nao encontrei notas na apuracao de "
          + mes(p.get("mes")).map(MES::format).orElse("mes consultado") + ".";
      case "proximosVencimentos" -> "Nao encontrei guias a vencer de " + DATA.format(hoje)
          + " a " + DATA.format(hoje.plusDays(dias(p.get("dias")))) + ".";
      case "guiasEmAtraso" -> "Nao encontrei guias em atraso (nao pagas com vencimento ate "
          + DATA.format(hoje.minusDays(1)) + ").";
      case "vencimentosDoMes" -> "Nao encontrei guias para pagar em "
          + mes(p.get("mes")).map(MES::format).orElse("mes consultado") + ".";
      case "consultarNota" -> "Nao encontrei a nota " + p.get("numero") + " nas suas notas.";
      case "listarAlertas" -> "Nao encontrei alertas com prazo nos proximos dias.";
      default -> "Nao encontrei dados para essa consulta.";
    };
  }

  private static String periodo(Map<String, String> p) {
    Optional<LocalDate> inicio = data(p.get("dataInicio"));
    Optional<LocalDate> fim = data(p.get("dataFim"));
    return inicio.isPresent() && fim.isPresent()
        ? " entre " + DATA.format(inicio.get()) + " e " + DATA.format(fim.get())
        : "";
  }

  private static String tipo(String tipo) {
    if ("SAIDA".equals(tipo)) {
      return " de venda";
    }
    return "ENTRADA".equals(tipo) ? " de compra" : "";
  }

  private static String situacao(String situacao) {
    return situacao == null ? "" : " " + SITUACAO.getOrDefault(situacao, situacao.toLowerCase(
        Locale.ROOT));
  }

  private static int dias(String valor) {
    try {
      return valor == null ? 30 : Integer.parseInt(valor);
    } catch (NumberFormatException e) {
      return 30;
    }
  }

  private static Optional<LocalDate> data(String valor) {
    try {
      return valor == null ? Optional.empty() : Optional.of(LocalDate.parse(valor));
    } catch (DateTimeParseException e) {
      return Optional.empty();
    }
  }

  private static Optional<YearMonth> mes(String valor) {
    try {
      return valor == null ? Optional.empty() : Optional.of(YearMonth.parse(valor));
    } catch (DateTimeParseException e) {
      return Optional.empty();
    }
  }

  private static String normalizar(String texto) {
    return DIACRITICOS.matcher(Normalizer.normalize(texto, Normalizer.Form.NFD)).replaceAll("")
        .toLowerCase(Locale.ROOT);
  }
}
