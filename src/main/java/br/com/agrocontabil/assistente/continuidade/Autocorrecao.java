package br.com.agrocontabil.assistente.continuidade;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Textos da autocorreção dos dados (#499): as aberturas que o backend põe antes da resposta nova,
 * depois de todos os validadores, e a descrição D do que foi consultado. A comparação é com a
 * carga assinada do par, nunca com o texto dele. "Corrijo" só sai quando a regra achou a consulta
 * anterior incoerente, a nova bate e o resultado mudou; na reexecução, resultado diferente é
 * "mudou desde" (dados novos ou o dia virou).
 */
public final class Autocorrecao {

  /** Reexecução com o mesmo resultado. */
  public static final String REPETI_IGUAL = "Repeti a mesma consulta (%s) e o resultado é o "
      + "mesmo.";

  /** Reexecução com resultado diferente. */
  public static final String REPETI_MUDOU = "Repeti a mesma consulta (%s) e o resultado mudou "
      + "desde a resposta anterior. Vale este:";

  /** Consulta anterior incoerente, a nova bate e mudou. */
  public static final String CORRIJO = "Conferi de novo e corrijo a resposta anterior: desta vez "
      + "consultei %s. Vale este:";

  /** Nova consulta com o mesmo resultado. */
  public static final String CONFERI_IGUAL = "Conferi de novo nos seus dados e o resultado é o "
      + "mesmo.";

  /** Consulta refeita, sem afirmar erro. */
  public static final String REFIZ = "Refiz a consulta (%s). O resultado é este:";

  /** Confirmação de par sem consulta. */
  public static final String CONFERI = "Conferi de novo nos seus dados.";

  /** Contestação com conteúdo novo. */
  public static final String REFIZ_COM_O_QUE_DISSE = "Refiz a consulta com o que você disse "
      + "(%s):";

  /** Dica depois da contestação que recebeu o mesmo resultado. */
  public static final String DICA_CONTESTACAO = "Se você esperava outro resultado, me diga o que "
      + "procura: o mês, o produto ou a nota.";

  /** A reexecução falhou: texto fixo, sem modelo, crédito nem {@code turno}. */
  public static final String FALHA_REEXECUCAO = "Não consegui refazer a consulta anterior agora. "
      + "Escreva a pergunta completa, por favor.";

  /** A situação da nota em D (o mapa do redator, no plural da lista). */
  private static final Map<String, String> SITUACAO = Map.ofEntries(
      Map.entry("RASCUNHO", "em rascunho"),
      Map.entry("COM_INCONSISTENCIAS", "com inconsistências a corrigir"),
      Map.entry("AGUARDANDO_APROVACAO", "aguardando aprovação do contador"),
      Map.entry("APROVADA", "aprovada pelo contador"),
      Map.entry("DEVOLVIDA", "devolvida pelo contador"),
      Map.entry("XML_GERADO", "com XML gerado, ainda não enviada"),
      Map.entry("ENVIANDO", "sendo enviada à SEFAZ"),
      Map.entry("AUTORIZADA", "autorizada"),
      Map.entry("REJEITADA", "rejeitada pela SEFAZ"),
      Map.entry("PENDENTE_SEFAZ", "aguardando a SEFAZ"),
      Map.entry("CANCELANDO", "em cancelamento"),
      Map.entry("CANCELADA", "cancelada"));

  private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
  private static final DateTimeFormatter MES = DateTimeFormatter.ofPattern("MM/yyyy");

  private Autocorrecao() {
  }

  /**
   * D: o que foi consultado, em português, rótulos juntados por ", " e " e ".
   *
   * @param consulta chamadas executadas
   * @return a descrição
   */
  public static String descrever(List<ChamadaAssinada> consulta) {
    List<String> rotulos = new ArrayList<>();
    for (ChamadaAssinada c : consulta) {
      String rotulo = rotulo(c);
      if (!rotulos.contains(rotulo)) {
        rotulos.add(rotulo);
      }
    }
    if (rotulos.isEmpty()) {
      return "os seus dados";
    }
    if (rotulos.size() == 1) {
      return rotulos.get(0);
    }
    return String.join(", ", rotulos.subList(0, rotulos.size() - 1)) + " e "
        + rotulos.get(rotulos.size() - 1);
  }

  static String rotulo(ChamadaAssinada c) {
    String base = switch (c.funcao()) {
      case "totalVendasPorProduto" -> "vendas de " + valor(c, "descricaoOuNcm");
      case "totalComprasPorInsumo" -> "compras de " + valor(c, "descricaoOuNcm");
      case "listarNotas" -> notas(c);
      case "consultarNota" -> "a nota " + valor(c, "numero");
      case "consultarApuracao" -> "a apuração de " + mes(c.argumento("mes"));
      case "vencimentosDoMes" -> "os vencimentos de " + mes(c.argumento("mes"));
      case "consultarCreditos" -> "seus créditos";
      case "consultarRetencoes" -> "suas retenções";
      case "proximosVencimentos" -> "os vencimentos dos próximos "
          + (c.argumento("dias") == null ? "30" : c.argumento("dias")) + " dias";
      case "guiasEmAtraso" -> "as guias em atraso";
      case "listarAlertas" -> "os prazos dos próximos dias";
      case "resumoDoProdutor" -> "o resumo das suas pendências";
      case "consultarTratamentoProduto" -> "o tratamento de " + valor(c, "produtoOuNcm")
          + " na reforma";
      default -> "seus dados";
    };
    return base + faixaDeDatas(c);
  }

  private static String notas(ChamadaAssinada c) {
    String tipo = c.argumento("tipo");
    String situacao = c.argumento("situacao");
    boolean semFiltro = (tipo == null || tipo.isBlank()) && (situacao == null || situacao.isBlank())
        && c.argumento("dataInicio") == null && c.argumento("dataFim") == null;
    if (semFiltro) {
      return "suas notas mais recentes";
    }
    StringBuilder sb = new StringBuilder("suas notas");
    if ("SAIDA".equalsIgnoreCase(tipo)) {
      sb.append(" de venda");
    } else if ("ENTRADA".equalsIgnoreCase(tipo)) {
      sb.append(" de compra");
    }
    if (situacao != null && !situacao.isBlank()) {
      String descricao = SITUACAO.get(situacao.strip().toUpperCase(Locale.ROOT));
      if (descricao != null) {
        sb.append(" com a situação ").append(descricao);
      }
    }
    return sb.toString();
  }

  private static String faixaDeDatas(ChamadaAssinada c) {
    String inicio = data(c.argumento("dataInicio"));
    String fim = data(c.argumento("dataFim"));
    if (inicio == null || fim == null) {
      return "";
    }
    return " de " + inicio + " a " + fim;
  }

  private static String valor(ChamadaAssinada c, String nome) {
    String valor = c.argumento(nome);
    return valor == null || valor.isBlank() ? "?" : valor.strip();
  }

  private static String data(String iso) {
    if (iso == null) {
      return null;
    }
    try {
      return LocalDate.parse(iso.strip()).format(DATA);
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  private static String mes(String iso) {
    if (iso == null) {
      return "o mês atual";
    }
    try {
      return YearMonth.parse(iso.strip()).format(MES);
    } catch (DateTimeParseException e) {
      return iso;
    }
  }
}
