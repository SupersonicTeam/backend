package br.com.agrocontabil.assistente;

import br.com.agrocontabil.assistente.ferramentas.TemplateTratamentoProduto;
import br.com.agrocontabil.common.NormalizacaoBusca;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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
 * <p>Regra: considere as consultas que voltaram vazias e cuja função não achou nada em nenhuma
 * outra chamada (uma nova tentativa com outro termo que achou dados não deixa negativa a trocar).
 * Toda frase sobre dados com negativa absoluta ("não tem", "não há", "não encontrei", "nenhum",
 * "não teve"...) que não traz o <strong>recorte completo</strong> de uma dessas consultas sai, e a
 * resposta ganha a frase fixa de cada uma delas, com o período e o filtro reais. Recorte completo é
 * o período (as datas, o mês ou o número) <strong>e</strong> o filtro (a situação da nota, o
 * produto): "entre 30/07 e 28/10 você não tem notas devolvidas", sobre uma consulta de notas
 * rejeitadas, não traz o filtro e sai. Mostrar o filtro expõe a escolha errada do modelo: o produtor
 * lê "Não encontrei notas rejeitadas pela SEFAZ entre...", e não "não há notas devolvidas". O resto
 * da resposta fica, com a formatação dela. Sem consulta vazia, nada muda.
 */
@Component
public class VerificadorNegativaDados {

  private static final Logger log = LoggerFactory.getLogger(VerificadorNegativaDados.class);

  /** Negativa absoluta, já sem acento, em minúsculas e com espaços simples. */
  private static final Pattern NEGATIVA = Pattern.compile("\\b("
      + "nao (tem|tens|ha|houve|teve|tiveram|possui|possuem|existe|existem|consta|constam|deve|"
      + "devem|encontrei|encontramos|comprou|vendeu|pagou|emitiu|foram encontrad\\w*|"
      + "foi encontrad\\w*)|nenhum|nenhuma|"
      + "inexistente|inexistentes)\\b");

  /** A frase fala de dado do produtor (e não de uma recusa ou de uma regra). */
  private static final Pattern SOBRE_DADOS = Pattern.compile("\\b(notas?|guias?|compras?|"
      + "vendas?|vendeu|comprou|debitos?|creditos?|impostos?|alertas?|apuracao|pagamentos?|pagar|"
      + "atras\\w*|vencid\\w*|vencimentos?|devolvid\\w*|rejeitad\\w*|registros?)\\b");

  /** Abreviações cujo ponto não termina a frase ("art. 30", "inc. IV"). */
  private static final Set<String> ABREVIACOES =
      Set.of("art", "arts", "inc", "incs", "n", "nº", "par", "p", "sr", "sra", "dr", "dra", "ex");

  /**
   * Uma frase e o separador que a segue, como estavam no texto.
   *
   * @param frase     a frase
   * @param separador espaço ou quebra de linha depois dela (vazio no fim)
   */
  record Frase(String frase, String separador) {}

  private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
  private static final DateTimeFormatter MES = DateTimeFormatter.ofPattern("MM/yyyy");
  private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");

  /** Como cada situação de nota aparece na frase fixa (o filtro que o modelo escolheu). */
  private static final Map<String, String> SITUACAO = Map.ofEntries(
      Map.entry("RASCUNHO", "em rascunho"),
      Map.entry("COM_INCONSISTENCIAS", "com inconsistências"),
      Map.entry("AGUARDANDO_APROVACAO", "aguardando aprovação do contador"),
      Map.entry("APROVADA", "aprovadas pelo contador"),
      Map.entry("DEVOLVIDA", "devolvidas pelo contador"),
      Map.entry("XML_GERADO", "com XML gerado e não enviadas"),
      Map.entry("ENVIANDO", "sendo enviadas à SEFAZ"),
      Map.entry("AUTORIZADA", "autorizadas"),
      Map.entry("REJEITADA", "rejeitadas pela SEFAZ"),
      Map.entry("PENDENTE_SEFAZ", "aguardando a SEFAZ"),
      Map.entry("CANCELANDO", "em cancelamento"),
      Map.entry("CANCELADA", "canceladas"));

  /** Radical que conta como a situação dita na frase do modelo ("devolvidas", "devolvida"...). */
  private static final Map<String, String> RADICAL_SITUACAO = Map.ofEntries(
      Map.entry("RASCUNHO", "rascunho"), Map.entry("COM_INCONSISTENCIAS", "inconsistenc"),
      Map.entry("AGUARDANDO_APROVACAO", "aprovac"), Map.entry("APROVADA", "aprovad"),
      Map.entry("DEVOLVIDA", "devolvid"), Map.entry("XML_GERADO", "xml"),
      Map.entry("ENVIANDO", "envi"), Map.entry("AUTORIZADA", "autorizad"),
      Map.entry("REJEITADA", "rejeitad"), Map.entry("PENDENTE_SEFAZ", "sefaz"),
      Map.entry("CANCELANDO", "cancel"), Map.entry("CANCELADA", "cancelad"));

  /**
   * A resposta com as negativas sem recorte trocadas, ou vazio se nada mudou.
   *
   * @param texto    resposta da rota de dados, já aceita pelo {@code ValidadorNumeros}
   * @param contexto contexto da pergunta (as consultas e o "hoje")
   * @return o texto novo, se houve troca
   */
  public Optional<String> substituir(String texto, ContextoAssistente contexto) {
    List<FonteDado> vazias = semResultadoEmNenhumaChamada(contexto);
    if (vazias.isEmpty() || texto == null || texto.isBlank()) {
      return Optional.empty();
    }
    StringBuilder mantido = new StringBuilder();
    int trocadas = 0;
    for (Frase f : frases(texto.strip())) {
      String normalizada = NormalizacaoBusca.normalizar(f.frase());
      boolean negativaSobreDados = NEGATIVA.matcher(normalizada).find()
          && SOBRE_DADOS.matcher(normalizada).find();
      if (negativaSobreDados && vazias.stream().noneMatch(v -> temRecorte(f.frase(), v))) {
        trocadas++;
      } else {
        mantido.append(f.frase()).append(f.separador());
      }
    }
    if (trocadas == 0) {
      return Optional.empty();
    }
    String resto = mantido.toString().strip();
    String restoNormalizado = NormalizacaoBusca.normalizar(resto);
    Set<String> frasesFixas = new LinkedHashSet<>();
    for (FonteDado f : vazias) {
      String fixa = fraseFixa(f, contexto.hoje());
      // A observação da função que o modelo já repassou não entra duas vezes.
      if (!restoNormalizado.contains(NormalizacaoBusca.normalizar(fixa))) {
        frasesFixas.add(fixa);
      }
    }
    // Só o tipo da troca vai ao log: o texto pode ter dado do produtor.
    log.info("Ceres: {} negativa(s) sem recorte trocada(s) pela frase do backend ({} consulta(s) "
        + "vazia(s))", trocadas, vazias.size());
    String fixas = String.join(" ", frasesFixas);
    if (resto.isEmpty()) {
      return Optional.of(fixas.isEmpty() ? texto.strip() : fixas);
    }
    return Optional.of(fixas.isEmpty() ? resto : resto + "\n\n" + fixas);
  }

  /**
   * Separa o texto em frases, guardando o separador de cada uma para remontar a resposta com a
   * formatação dela. Termina a frase: ponto, exclamação, interrogação ou ponto e vírgula seguidos de
   * espaço, e quebra de linha. Não termina: ponto de abreviação ("art. 30") e de item numerado
   * no começo da linha ("2. ").
   */
  static List<Frase> frases(String texto) {
    List<Frase> frases = new ArrayList<>();
    int inicio = 0;
    int i = 0;
    while (i < texto.length()) {
      char c = texto.charAt(i);
      boolean quebra = c == '\n';
      boolean pontuacao = ".!?;".indexOf(c) >= 0 && i + 1 < texto.length()
          && Character.isWhitespace(texto.charAt(i + 1)) && !pontoQueNaoTermina(texto, inicio, i);
      if (!quebra && !pontuacao) {
        i++;
        continue;
      }
      int fimFrase = quebra ? i : i + 1;
      int fimSeparador = fimFrase;
      while (fimSeparador < texto.length() && Character.isWhitespace(texto.charAt(fimSeparador))) {
        fimSeparador++;
      }
      frases.add(new Frase(texto.substring(inicio, fimFrase),
          texto.substring(fimFrase, fimSeparador)));
      inicio = fimSeparador;
      i = fimSeparador;
    }
    if (inicio < texto.length()) {
      frases.add(new Frase(texto.substring(inicio), ""));
    }
    return frases;
  }

  private static boolean pontoQueNaoTermina(String texto, int inicioFrase, int ponto) {
    if (texto.charAt(ponto) != '.') {
      return false;
    }
    int j = ponto;
    while (j > inicioFrase && !Character.isWhitespace(texto.charAt(j - 1))
        && texto.charAt(j - 1) != '(') {
      j--;
    }
    String palavra = texto.substring(j, ponto).toLowerCase(Locale.ROOT);
    boolean itemNumerado = j == inicioFrase && palavra.matches("\\d{1,3}");
    return itemNumerado || ABREVIACOES.contains(palavra);
  }

  /** Consultas vazias de funções que não acharam nada em nenhuma chamada desta pergunta. */
  static List<FonteDado> semResultadoEmNenhumaChamada(ContextoAssistente contexto) {
    List<FonteDado> vazias = contexto.fontesVazias();
    List<FonteDado> todas = new ArrayList<>(contexto.fontes());
    vazias.forEach(todas::remove);
    Set<String> comResultado = todas.stream().map(FonteDado::funcao).collect(Collectors.toSet());
    return vazias.stream().filter(f -> !comResultado.contains(f.funcao())).toList();
  }

  /**
   * A frase traz o recorte completo da consulta: o período (todas as datas, o mês ou o número) e,
   * quando houve, o filtro (a situação da nota ou o produto). Números casam inteiros: "15 dias" não
   * é recorte de "5 dias".
   */
  static boolean temRecorte(String frase, FonteDado fonte) {
    String normalizada = NormalizacaoBusca.normalizar(frase);
    Map<String, String> p = fonte.parametros();
    List<String> periodo = new ArrayList<>();
    data(p.get("dataInicio")).ifPresent(d -> periodo.add(DATA.format(d)));
    data(p.get("dataFim")).ifPresent(d -> periodo.add(DATA.format(d)));
    boolean periodoOk;
    if (!periodo.isEmpty()) {
      periodoOk = periodo.stream().allMatch(t -> contemNumero(frase, t));
    } else if (p.containsKey("mes")) {
      periodoOk = mes(p.get("mes")).map(m -> contemNumero(frase, MES.format(m))
          || normalizada.contains(NormalizacaoBusca.normalizar(nomeDoMes(m)))).orElse(false);
    } else if (p.containsKey("numero")) {
      periodoOk = contemNumero(frase, p.get("numero"));
    } else if (p.containsKey("dias")) {
      periodoOk = contemNumero(frase, p.get("dias")) && normalizada.contains("dia");
    } else {
      // Sem período a citar (guiasEmAtraso, listarAlertas): só a frase do backend tem o recorte.
      periodoOk = false;
    }
    if (!periodoOk) {
      return false;
    }
    String situacao = p.get("situacao");
    if (situacao != null && !normalizada.contains(
        RADICAL_SITUACAO.getOrDefault(situacao, situacao.toLowerCase(Locale.ROOT)))) {
      return false;
    }
    String produto = p.get("descricaoOuNcm");
    return produto == null || normalizada.contains(NormalizacaoBusca.normalizar(produto));
  }

  private static boolean contemNumero(String frase, String token) {
    return Pattern.compile("(?<![\\d/])" + Pattern.quote(token) + "(?![\\d/])").matcher(frase)
        .find();
  }

  /**
   * Frase fixa de uma consulta vazia, com o recorte e o filtro reais. Também é a frase da parte de
   * dados do MISTO sem nada encontrado.
   *
   * @param fonte consulta que voltou vazia
   * @param hoje  "hoje" da pergunta
   * @return a frase, sem recado ao modelo
   */
  public static String fraseFixa(FonteDado fonte, LocalDate hoje) {
    Map<String, String> p = fonte.parametros();
    String periodo = periodo(p);
    return switch (fonte.funcao()) {
      case "listarNotas" -> "Não encontrei notas" + tipo(p.get("tipo"))
          + situacao(p.get("situacao")) + periodo + ".";
      case "totalComprasPorInsumo" -> "Não encontrei compras " + produto(p.get("descricaoOuNcm"))
          + periodo + ".";
      case "totalVendasPorProduto" -> "Não encontrei vendas autorizadas "
          + produto(p.get("descricaoOuNcm")) + periodo + ".";
      case "consultarCreditos" -> "Não encontrei compras com crédito" + periodo + ".";
      case "consultarRetencoes" -> "Não encontrei vendas autorizadas" + periodo + ".";
      case "consultarApuracao" -> "Não encontrei notas na apuração de "
          + mes(p.get("mes")).map(MES::format).orElse("mês consultado") + ".";
      case "proximosVencimentos" -> "Não encontrei guias a vencer de " + DATA.format(hoje)
          + " a " + DATA.format(hoje.plusDays(dias(p.get("dias")))) + ".";
      case "guiasEmAtraso" -> "Não encontrei guias em atraso (não pagas com vencimento até "
          + DATA.format(hoje.minusDays(1)) + ").";
      case "vencimentosDoMes" -> "Não encontrei guias para pagar em "
          + mes(p.get("mes")).map(MES::format).orElse("mês consultado") + ".";
      case "consultarNota" -> "Não encontrei a nota " + p.get("numero") + " nas suas notas.";
      case "listarAlertas" -> "Não encontrei alertas com prazo nos próximos dias.";
      case "resumoDoProdutor" -> "Não encontrei pontos de atenção nos seus dados: guias em"
          + " atraso, prazos dos próximos dias, notas paradas, pedidos do escritório e notas a"
          + " conferir.";
      case "consultarTratamentoProduto" ->
          TemplateTratamentoProduto.naoEncontrado(p.get("produtoOuNcm"));
      default -> "Não encontrei dados para essa consulta.";
    };
  }

  private static String produto(String termo) {
    if (termo == null) {
      return "desse produto";
    }
    return termo.chars().allMatch(Character::isDigit) ? "do NCM " + termo : "de " + termo;
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

  private static String nomeDoMes(YearMonth mes) {
    return mes.getMonth().getDisplayName(TextStyle.FULL, PT_BR) + " de " + mes.getYear();
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
}
