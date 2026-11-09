package br.com.agrocontabil.assistente;

import br.com.agrocontabil.assistente.PeriodoDaPergunta.Periodo;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Período aberto ou contado de um ponto ("até setembro", "desde o mês passado", "do mês retrasado
 * pra cá", "do ano passado pra trás") e a safra numerada ("safra 25/26"), lidos pelo backend
 * (issue #452, rodada 2). Complementa o {@link PeriodoDaPergunta}, que só lê o período fechado.
 *
 * <p>Uma âncora só (mês passado ou retrasado, ano passado ou retrasado, mês por extenso com ou
 * sem ano, ano) e uma operação só. Com duas âncoras ("de março até setembro"), data com dia ou
 * operação sem âncora conhecida, não resolve: as datas ficam com o modelo.
 *
 * <ul>
 *   <li>"desde", "a partir de", "pra cá", "em diante": do primeiro dia da âncora até hoje;</li>
 *   <li>"até", "pra trás": do início aberto até o último dia da âncora;</li>
 *   <li>"antes de": do início aberto até o dia anterior à âncora;</li>
 *   <li>"depois de", "após": do dia seguinte à âncora até hoje.</li>
 * </ul>
 *
 * <p>O início aberto é o mais antigo que as funções de dados aceitam: 24 meses antes do fim. Mês
 * por extenso sem ano é o mais recente que não passou do mês corrente; um mês ainda por vir no
 * ano, sem ano, é ambíguo e não resolve. A safra "AA/AA" vai de 1º de julho do primeiro ano a 30
 * de junho do segundo.
 */
final class PeriodoAberto {

  private static final String MESES = "janeiro|fevereiro|marco|abril|maio|junho|julho|agosto"
      + "|setembro|outubro|novembro|dezembro";

  private static final Map<String, Integer> NUMERO_DO_MES = Map.ofEntries(
      Map.entry("janeiro", 1), Map.entry("fevereiro", 2), Map.entry("marco", 3),
      Map.entry("abril", 4), Map.entry("maio", 5), Map.entry("junho", 6), Map.entry("julho", 7),
      Map.entry("agosto", 8), Map.entry("setembro", 9), Map.entry("outubro", 10),
      Map.entry("novembro", 11), Map.entry("dezembro", 12));

  /** Mês por extenso, com o ano opcional ("setembro", "setembro de 2025"). */
  static final Pattern MES_NOMEADO = Pattern.compile("\\b(" + MESES
      + ")(?: (?:de|do ano de) ((?:19|20)\\d{2}))?\\b");

  /** Ano solto, fora do número de uma nota. */
  private static final Pattern ANO = Pattern.compile(
      "(?<!nota )(?<!numero )(?<!n )\\b((?:19|20)\\d{2})\\b");

  /** Data com dia ("15/10"): o período não é de mês inteiro. */
  private static final Pattern DATA_COM_DIA = Pattern.compile("\\b\\d{1,2}/\\d{1,2}\\b");

  /** Âncora: o ponto de onde o período é contado. */
  private static final String ANCORA = "(?:(?:o|a) )?(mes (?:passado|anterior|retrasado)"
      + "|penultimo mes|ultimo mes|mes que passou|ano (?:passado|anterior|retrasado)"
      + "|(?:mes de )?(?:" + MESES + ")(?: (?:de|do ano de) (?:19|20)\\d{2})?"
      + "|(?:ano de )?(?:19|20)\\d{2})";

  private enum Operacao { DESDE, ATE, ANTES, DEPOIS }

  private record Forma(Pattern padrao, Operacao operacao) {}

  private static final List<Forma> FORMAS = List.of(
      forma("\\bdesde " + ANCORA + "\\b", Operacao.DESDE),
      forma("\\ba partir d[eoa]s? " + ANCORA + "\\b", Operacao.DESDE),
      forma("\\bd[eoa]s? " + ANCORA + " (?:pra|para) ca\\b", Operacao.DESDE),
      forma("\\bd[eoa]s? " + ANCORA + " em diante\\b", Operacao.DESDE),
      forma("\\bate (?:[oa] )?" + ANCORA + "\\b", Operacao.ATE),
      forma("\\bd[eoa]s? " + ANCORA + " (?:pra|para) tras\\b", Operacao.ATE),
      forma("\\bantes d[eoa]s? " + ANCORA + "\\b", Operacao.ANTES),
      forma("\\bdepois d[eoa]s? " + ANCORA + "\\b", Operacao.DEPOIS),
      forma("\\bapos (?:[oa] )?" + ANCORA + "\\b", Operacao.DEPOIS));

  /** Safra numerada: "safra 25/26", "safra 2025/2026", "safra 2025/26". */
  private static final Pattern SAFRA = Pattern.compile(
      "\\bsafras? (?:de )?(\\d{2}|\\d{4}) ?/ ?(\\d{2}|\\d{4})\\b");

  private PeriodoAberto() {}

  /** A pergunta cita uma safra numerada. */
  static boolean temSafraNumerada(String texto) {
    return SAFRA.matcher(texto).find();
  }

  /**
   * A safra numerada, de 1º de julho a 30 de junho, se for a única referência de tempo da
   * pergunta e já tiver começado.
   *
   * @param texto pergunta normalizada ({@link PeriodoDaPergunta#normalizar})
   * @param hoje  data de referência
   * @return o período da safra, ou vazio
   */
  static Optional<Periodo> safra(String texto, LocalDate hoje) {
    Matcher m = SAFRA.matcher(texto);
    if (!m.find()) {
      return Optional.empty();
    }
    int primeiro = ano(m.group(1));
    int segundo = ano(m.group(2));
    String resto = texto.substring(0, m.start()) + " " + texto.substring(m.end());
    if (segundo != primeiro + 1 || SAFRA.matcher(resto).find() || temOutraReferencia(resto)) {
      return Optional.empty();
    }
    LocalDate inicio = LocalDate.of(primeiro, 7, 1);
    return inicio.isAfter(hoje) ? Optional.empty()
        : Optional.of(new Periodo(inicio, LocalDate.of(segundo, 6, 30), null));
  }

  /**
   * O período aberto ou contado de uma âncora.
   *
   * @param texto pergunta normalizada ({@link PeriodoDaPergunta#normalizar})
   * @param hoje  data de referência
   * @return o período, ou vazio quando a leitura não é inequívoca
   */
  static Optional<Periodo> resolver(String texto, LocalDate hoje) {
    if (DATA_COM_DIA.matcher(texto).find() || ancoras(texto) != 1) {
      return Optional.empty();
    }
    Operacao operacao = null;
    String ancora = null;
    for (Forma forma : FORMAS) {
      Matcher m = forma.padrao().matcher(texto);
      while (m.find()) {
        if (operacao != null && (operacao != forma.operacao() || !ancora.equals(m.group(1)))) {
          return Optional.empty();
        }
        operacao = forma.operacao();
        ancora = m.group(1);
      }
    }
    if (operacao == null) {
      return Optional.empty();
    }
    Optional<Periodo> ponto = ancora(ancora, hoje);
    if (ponto.isEmpty()) {
      return Optional.empty();
    }
    LocalDate a0 = ponto.get().inicio();
    LocalDate a1 = ponto.get().fim();
    return switch (operacao) {
      case DESDE -> a0.isAfter(hoje) ? Optional.empty()
          : Optional.of(new Periodo(a0, hoje, null));
      case ATE -> Optional.of(abertoAte(a1));
      case ANTES -> Optional.of(abertoAte(a0.minusDays(1)));
      case DEPOIS -> a1.isBefore(hoje)
          ? Optional.of(new Periodo(a1.plusDays(1), hoje, null)) : Optional.empty();
    };
  }

  /**
   * Os meses por extenso da pergunta, cada um com o ano dele ou o mais recente que não passou do
   * mês corrente. Vazio se algum for ambíguo (mês ainda por vir no ano, sem ano).
   */
  static List<YearMonth> mesesNomeados(String texto, LocalDate hoje) {
    List<YearMonth> meses = new ArrayList<>();
    Matcher m = MES_NOMEADO.matcher(texto);
    while (m.find()) {
      Optional<YearMonth> mes = mes(m.group(1), m.group(2), hoje);
      if (mes.isEmpty()) {
        return List.of();
      }
      if (!meses.contains(mes.get())) {
        meses.add(mes.get());
      }
    }
    return meses;
  }

  /** Início aberto: o mais antigo que as funções aceitam (24 meses até o fim). */
  private static Periodo abertoAte(LocalDate fim) {
    return new Periodo(fim.minusMonths(PeriodoDaPergunta.MAXIMO_MESES).plusDays(1), fim, null);
  }

  /**
   * Quantas referências de tempo a pergunta tem: expressões relativas, meses (com o ano deles) e
   * anos soltos.
   */
  private static int ancoras(String texto) {
    int n = PeriodoDaPergunta.expressoesRelativas(texto);
    Matcher meses = MES_NOMEADO.matcher(texto);
    StringBuilder semMeses = new StringBuilder();
    while (meses.find()) {
      n++;
      meses.appendReplacement(semMeses, " # ");
    }
    meses.appendTail(semMeses);
    Matcher anos = ANO.matcher(semMeses);
    while (anos.find()) {
      n++;
    }
    return n;
  }

  private static boolean temOutraReferencia(String texto) {
    return ancoras(texto) > 0 || DATA_COM_DIA.matcher(texto).find();
  }

  private static Optional<Periodo> ancora(String ancora, LocalDate hoje) {
    String a = ancora.replaceFirst("^(?:mes de |ano de )", "");
    YearMonth atual = YearMonth.from(hoje);
    switch (a) {
      case "mes passado", "mes anterior", "ultimo mes", "mes que passou":
        return Optional.of(doMes(atual.minusMonths(1)));
      case "mes retrasado", "penultimo mes":
        return Optional.of(doMes(atual.minusMonths(2)));
      case "ano passado", "ano anterior":
        return Optional.of(doAno(hoje.getYear() - 1));
      case "ano retrasado":
        return Optional.of(doAno(hoje.getYear() - 2));
      default:
        break;
    }
    if (a.matches("(?:19|20)\\d{2}")) {
      return Optional.of(doAno(Integer.parseInt(a)));
    }
    Matcher m = MES_NOMEADO.matcher(a);
    return m.matches() ? mes(m.group(1), m.group(2), hoje).map(PeriodoAberto::doMes)
        : Optional.empty();
  }

  private static Optional<YearMonth> mes(String nome, String ano, LocalDate hoje) {
    int numero = NUMERO_DO_MES.get(nome);
    YearMonth atual = YearMonth.from(hoje);
    if (ano != null) {
      return Optional.of(YearMonth.of(Integer.parseInt(ano), numero));
    }
    YearMonth desteAno = YearMonth.of(hoje.getYear(), numero);
    return desteAno.isAfter(atual) ? Optional.empty() : Optional.of(desteAno);
  }

  private static Periodo doMes(YearMonth mes) {
    return new Periodo(mes.atDay(1), mes.atEndOfMonth(), mes);
  }

  private static Periodo doAno(int ano) {
    return new Periodo(LocalDate.of(ano, 1, 1), LocalDate.of(ano, 12, 31), null);
  }

  private static int ano(String valor) {
    return valor.length() == 2 ? 2000 + Integer.parseInt(valor) : Integer.parseInt(valor);
  }

  private static Forma forma(String padrao, Operacao operacao) {
    return new Forma(Pattern.compile(padrao), operacao);
  }
}
