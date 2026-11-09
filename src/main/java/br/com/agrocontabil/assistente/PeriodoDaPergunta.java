package br.com.agrocontabil.assistente;

import br.com.agrocontabil.common.NormalizacaoBusca;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Período relativo da pergunta ("mês passado", "este ano", "último trimestre", "nos últimos seis
 * meses"), resolvido pelo backend a partir da data de referência, no fuso de Brasília (issue #442).
 *
 * <p>Antes, o modelo calculava as datas sozinho a partir da linha "Hoje é": trocava o mês corrente
 * por uma janela de três meses ou desistia de chamar a função. Agora a data é do backend: a
 * {@link RotaDados} mostra o período ao modelo e, depois da escolha das funções, grava as datas
 * nos parâmetros de período de cada função ({@link #aplicar}).
 *
 * <p><strong>Só o inequívoco.</strong> Resolve quando a pergunta tem uma expressão relativa só (ou
 * várias que dão o mesmo período) e nada que mude o sentido dela. Sem resolver, quem preenche as
 * datas é o modelo, como antes do #442. Não resolve com (issue #452, item 3, e #442):
 *
 * <ul>
 *   <li>data explícita (mês por extenso, ano, dia/mês), fora do período aberto abaixo;</li>
 *   <li>parte do período ("a primeira quinzena do mês passado");</li>
 *   <li>período aberto ou contado de um ponto que o {@link PeriodoAberto} não lê com uma âncora
 *       e uma operação só ("de março até o mês passado", "do ano passado pra frente", "até
 *       15/01") e safra sem número ("safra do ano passado");</li>
 *   <li>dois períodos, inclusive o segundo só pelo adjetivo ("no mês passado e no retrasado",
 *       "este mês e o próximo");</li>
 *   <li>frase de regra, em que o mês é contado a partir de um fato ("vence no mês seguinte ao da
 *       venda").</li>
 * </ul>
 *
 * <p>Os períodos:
 *
 * <ul>
 *   <li>Mês e ano correntes (e trimestre e semestre correntes) vão do primeiro dia até hoje. Em
 *       pergunta de vencimento (guia, boleto, DARF, prazo, pagar, recolher, débito) vão até o
 *       último dia: o que vence depois de hoje ainda é deste mês (#452, item 2). As funções de
 *       datas (vendas, compras, notas) seguem até hoje.</li>
 *   <li>Mês, semestre e ano passados são os do calendário, inteiros.</li>
 *   <li>"Último trimestre" e "trimestre passado/anterior" são os três últimos meses fechados: os
 *       três meses do calendário inteiros antes do mês corrente (decisão do Murillo, 2026-11-09,
 *       #452, item 4). Em 09/11/2026, de 01/08 a 31/10.</li>
 *   <li>"Últimos três meses" ("últimos 3 meses", "três últimos meses") são os mesmos três
 *       meses fechados do "último trimestre" (segunda decisão do Murillo, 2026-11-09, #452). Os
 *       demais "últimos N meses" e os "últimos N dias" são a janela móvel que termina hoje, e "N
 *       últimos meses" com N diferente de três não resolve: esses não foram decididos.</li>
 *   <li>Período aberto e safra numerada (#452, rodada 2): ver {@link PeriodoAberto} ("desde o mês
 *       passado", "até setembro", "do mês retrasado pra cá", "safra 25/26"). "Até hoje", "até
 *       agora" e "até o momento" são o período em curso.</li>
 * </ul>
 *
 * <p>No mês corrente, {@link Periodo#diasAteOFimDoMes()} limita a janela de
 * {@code proximosVencimentos} ao último dia; {@link #mesesDaApuracao} dá os meses de uma pergunta
 * de apuração em que o modelo não escolheu função (#452, rodada 2).
 */
public final class PeriodoDaPergunta {

  /** Funções que recebem {@code dataInicio}/{@code dataFim}. */
  public static final Set<String> FUNCOES_DE_DATAS = Set.of("totalComprasPorInsumo",
      "totalVendasPorProduto", "consultarCreditos", "consultarRetencoes", "listarNotas");

  /** Funções que recebem um mês ({@code mes}, AAAA-MM). */
  public static final Set<String> FUNCOES_DE_MES = Set.of("consultarApuracao", "vencimentosDoMes");

  /** Guias a vencer nos próximos dias a partir de hoje (#452, item 1). */
  public static final String FUNCAO_PROXIMOS_VENCIMENTOS = "proximosVencimentos";

  /** Guias a pagar num mês do calendário (#452, item 1). */
  public static final String FUNCAO_VENCIMENTOS_DO_MES = "vencimentosDoMes";

  /** Apuração de IBS/CBS de um mês (#452, rodada 2). */
  public static final String FUNCAO_APURACAO = "consultarApuracao";

  /** Maior janela móvel aceita: a das funções de dados é de 24 meses. */
  static final int MAXIMO_MESES = 24;
  static final int MAXIMO_DIAS = 731;

  /**
   * "Último trimestre" e "últimos três meses": os três meses do calendário inteiros antes do mês
   * corrente (decisões do Murillo, 2026-11-09, #452).
   */
  static final int MESES_FECHADOS_DO_TRIMESTRE = 3;

  private static final String ESTE = "(?:n?est[ea]|n?ess[ea]|dest[ea]|dess[ea])";

  private static final Map<String, Integer> POR_EXTENSO = Map.ofEntries(
      Map.entry("um", 1), Map.entry("uma", 1), Map.entry("dois", 2), Map.entry("duas", 2),
      Map.entry("tres", 3), Map.entry("quatro", 4), Map.entry("cinco", 5), Map.entry("seis", 6),
      Map.entry("sete", 7), Map.entry("oito", 8), Map.entry("nove", 9), Map.entry("dez", 10),
      Map.entry("onze", 11), Map.entry("doze", 12), Map.entry("quinze", 15),
      Map.entry("dezoito", 18), Map.entry("vinte", 20), Map.entry("trinta", 30),
      Map.entry("sessenta", 60), Map.entry("noventa", 90));

  private static final String QUANTIDADE = "(\\d{1,3}|" + String.join("|", POR_EXTENSO.keySet())
      + ")";

  /**
   * Data explícita: com ela, o período não é relativo e quem preenche é o modelo. O ano só conta
   * fora do número de uma nota ("a nota 2045 do mês passado").
   */
  private static final Pattern DATA_EXPLICITA = Pattern.compile("\\b(janeiro|fevereiro|marco"
      + "|abril|maio|junho|julho|agosto|setembro|outubro|novembro|dezembro"
      + "|(?<!nota )(?<!numero )(?<!n )(19|20)\\d{2}|\\d{1,2}/\\d{1,2})\\b");

  /**
   * Parte de um período ("o primeiro trimestre do ano passado", "a primeira quinzena do mês
   * passado", "o mesmo mês do ano passado", "o fim do mês passado"): o período inteiro estaria
   * errado, então quem preenche é o modelo.
   */
  private static final Pattern SUBPERIODO = Pattern.compile("\\b(primeir[oa]s?|segund[oa]s?"
      + "|terceir[oa]s?|quart[oa]s?|quinzenas?|semanas?|bimestres?|metade|comeco|inicio|fim"
      + "|final|meados|mesm[oa] (mes|ano|periodo|epoca|trimestre|semestre)|dia \\d{1,2}"
      + "|ate o dia)\\b");

  /**
   * Período aberto ou contado de outro ponto ("desde o mês passado", "até o mês passado", "do ano
   * passado pra cá") e a safra (#452, item 3): o período inteiro estaria errado.
   */
  private static final Pattern ABERTO = Pattern.compile("\\b(desde|ate(?! (?:hoje|agora"
      + "|o momento)\\b)|antes|a partir|pra ca|para ca|pra tras|para tras|em diante"
      + "|pra frente|para frente|depois d[eoa]s?|apos|safras?)\\b");

  /**
   * Frase de regra: o mês é contado a partir de um fato, não de hoje ("vence no mês seguinte ao
   * da venda", "até o dia 20 do mês subsequente ao fato gerador") (#452, item 3).
   */
  private static final Pattern REGRA_DE_PRAZO = Pattern.compile("\\b(mes|ano|trimestre|semestre)"
      + " (seguinte|anterior|subsequente|posterior) (a|ao|aos|as|aquele|aquela)\\b");

  /**
   * Segundo período só pelo adjetivo, depois de tirar as expressões já lidas ("no mês passado e
   * no retrasado", "este mês e também para o próximo"): são dois períodos (#452, item 3). O
   * "retrasado" que sobra é sempre outro período; os demais adjetivos só contam depois de "e",
   * "ou", "com" ou "contra" ("e o próximo passo" também deixa de resolver: volta ao modelo).
   */
  private static final Pattern SEGUNDO_PERIODO = Pattern.compile("\\bretrasad[oa]s?\\b"
      + "|\\b(?:e|ou|com|contra|versus|vs)(?: tambem)?(?: (?:para|pra|pro|ate))?"
      + " (?:(?:n|d)?(?:o|a|os|as) )?(?:mes |ano |trimestre |semestre )?(?:passad[oa]s?"
      + "|anterior(?:es)?|seguintes?|proxim[oa]s?|que vem|atua(?:l|is)|corrente)\\b");

  /** Pergunta de apuração (IBS/CBS do mês): a classe 4 da rodada 2 da #452. */
  private static final Pattern APURACAO = Pattern.compile("\\b(apurac\\w*|saldo (?:de|do|da)"
      + " (?:ibs|cbs))\\b");

  /** Mais meses que isso, a apuração sem função escolhida fica com a mensagem de sempre. */
  static final int MAXIMO_MESES_APURACAO = 12;

  /** Pergunta de vencimento: o período em curso vai até o último dia (#452, item 2). */
  private static final Pattern VENCIMENTO = Pattern.compile("\\b(venc\\w*|guias?|boletos?"
      + "|darfs?|pagar|pagamentos?|quitar|recolh\\w*|debitos?|prazos?)\\b");

  /**
   * Janela em dias na pergunta ("nos próximos 15 dias") ou a próxima guia a partir de hoje ("quando
   * vence a minha próxima guia"): as guias dela não são as do mês futuro da pergunta, então
   * {@code proximosVencimentos} fica (#452, item 1). "Bom dia" não é janela.
   */
  private static final Pattern GUIAS_A_PARTIR_DE_HOJE = Pattern.compile("\\b" + QUANTIDADE
      + " dias\\b|\\bproxim[oa]s? (?:guias?|vencimentos?|boletos?|darfs?|pagamentos?)\\b");

  /** Expressão relativa → período, na ordem de prioridade de leitura. */
  private static final List<Regra> REGRAS = List.of(
      fixa("\\bmes (?:retrasado)\\b|\\bpenultimo mes\\b", h -> mes(h, -2)),
      fixa("\\bmes (?:passado|anterior)\\b|\\bultimo mes\\b|\\bmes que passou\\b",
          h -> mes(h, -1)),
      fixa("\\bmes que vem\\b|\\bproximo mes\\b|\\bmes seguinte\\b", h -> mes(h, 1)),
      emCurso("\\b" + ESTE + " mes\\b|\\bmes (?:atual|corrente)\\b",
          (h, venc) -> new Periodo(h.withDayOfMonth(1),
              venc ? YearMonth.from(h).atEndOfMonth() : h, YearMonth.from(h))),
      fixa("\\bano retrasado\\b", h -> ano(h, -2)),
      fixa("\\bano (?:passado|anterior)\\b", h -> ano(h, -1)),
      emCurso("\\b" + ESTE + " ano\\b|\\bano (?:atual|corrente)\\b",
          (h, venc) -> venc ? ano(h, 0) : intervalo(h.withDayOfYear(1), h)),
      fixa("\\bultimo trimestre\\b|\\btrimestre (?:passado|anterior)\\b",
          h -> mesesFechados(h, MESES_FECHADOS_DO_TRIMESTRE)),
      emCurso("\\b" + ESTE + " trimestre\\b|\\btrimestre (?:atual|corrente)\\b",
          (h, venc) -> venc ? trimestre(h) : intervalo(trimestre(h).inicio(), h)),
      fixa("\\bultimo semestre\\b|\\bsemestre (?:passado|anterior)\\b", h -> semestre(h, -1)),
      emCurso("\\b" + ESTE + " semestre\\b|\\bsemestre (?:atual|corrente)\\b",
          (h, venc) -> venc ? semestre(h, 0) : intervalo(semestre(h, 0).inicio(), h)),
      new Regra(Pattern.compile("\\bultimos " + QUANTIDADE + " meses\\b"),
          (h, m, venc) -> ultimosMeses(h, m.group(1))),
      fixa("\\b(?:3|tres) ultimos meses\\b",
          h -> mesesFechados(h, MESES_FECHADOS_DO_TRIMESTRE)),
      new Regra(Pattern.compile("\\bultimos " + QUANTIDADE + " dias\\b"),
          (h, m, venc) -> foraDoLimite(m.group(1), MAXIMO_DIAS) ? null
              : intervalo(h.minusDays(quantidade(m.group(1)) - 1L), h)));

  private PeriodoDaPergunta() {}

  /**
   * Período da pergunta.
   *
   * @param inicio                   primeiro dia
   * @param fim                      último dia
   * @param mes                      o mês, quando o período é um mês do calendário (passado,
   *                                 corrente ou futuro); {@code null} nos demais
   * @param fimDosDados              último dia para as funções de datas (vendas, compras,
   *                                 créditos, retenções, notas): o que já aconteceu vai até hoje,
   *                                 mesmo quando o período em curso de uma pergunta de vencimento
   *                                 vai até o fim do mês (#452, item 2)
   * @param vencimentosDoMesFuturo   o período é um mês depois do corrente e a pergunta não traz
   *                                 janela em dias nem a próxima guia: as guias a vencer da
   *                                 pergunta são as daquele mês, não as dos próximos 30 dias a
   *                                 partir de hoje (#452, item 1; ver {@code RotaDados.comPeriodo})
   * @param diasAteOFimDoMes         o período é o mês corrente: quantos dias faltam até o último
   *                                 dia dele (zero no último dia), o teto da janela de
   *                                 {@code proximosVencimentos} (#452, rodada 2); {@code -1} nos
   *                                 demais
   */
  public record Periodo(LocalDate inicio, LocalDate fim, YearMonth mes, LocalDate fimDosDados,
      boolean vencimentosDoMesFuturo, int diasAteOFimDoMes) {

    Periodo(LocalDate inicio, LocalDate fim, YearMonth mes) {
      this(inicio, fim, mes, fim, false, -1);
    }

    /** Linha para o modelo, antes da pergunta: as datas já calculadas. */
    public String paraOModelo() {
      return "Periodo da pergunta, ja calculado: de " + inicio + " a " + fim
          + (mes == null ? "" : " (mes " + mes + ")") + ".";
    }
  }

  /**
   * O período relativo da pergunta, se houver um só e nada que mude o sentido dele.
   *
   * @param pergunta pergunta do usuário
   * @param hoje     data de referência (Brasília)
   * @return o período, ou vazio
   */
  public static Optional<Periodo> resolver(String pergunta, LocalDate hoje) {
    String texto = normalizar(pergunta);
    if (texto.isEmpty() || SUBPERIODO.matcher(texto).find()
        || REGRA_DE_PRAZO.matcher(texto).find()) {
      return Optional.empty();
    }
    // Rodada 2 da #452: safra numerada e periodo aberto ou contado de um ponto tem leitura propria.
    if (PeriodoAberto.temSafraNumerada(texto)) {
      return PeriodoAberto.safra(texto, hoje).map(p -> completo(p, hoje, texto));
    }
    if (ABERTO.matcher(texto).find()) {
      return PeriodoAberto.resolver(texto, hoje)
          .filter(p -> !SEGUNDO_PERIODO.matcher(semExpressoesRelativas(texto)).find())
          .map(p -> completo(p, hoje, texto));
    }
    if (DATA_EXPLICITA.matcher(texto).find()) {
      return Optional.empty();
    }
    boolean vencimento = VENCIMENTO.matcher(texto).find();
    char[] semAsLidas = texto.toCharArray();
    Set<Periodo> achados = new LinkedHashSet<>();
    for (Regra regra : REGRAS) {
      Matcher m = regra.padrao().matcher(texto);
      while (m.find()) {
        Periodo p = regra.calculo().calcular(hoje, m, vencimento);
        if (p == null) {
          return Optional.empty();
        }
        achados.add(p);
        Arrays.fill(semAsLidas, m.start(), m.end(), '#');
      }
    }
    if (achados.size() != 1 || SEGUNDO_PERIODO.matcher(new String(semAsLidas)).find()) {
      return Optional.empty();
    }
    return Optional.of(completo(achados.iterator().next(), hoje, texto));
  }

  /**
   * Os meses da apuração pedida, para quando o modelo não escolheu função nenhuma (#452, rodada
   * 2): o período da pergunta, mês a mês até o mês corrente, ou os meses por extenso. Vazio se a
   * pergunta não é de apuração, se não há período ou se ele passa de 12 meses.
   *
   * @param pergunta pergunta do usuário
   * @param hoje     data de referência (Brasília)
   * @return os meses, do mais antigo ao mais recente
   */
  public static List<YearMonth> mesesDaApuracao(String pergunta, LocalDate hoje) {
    String texto = normalizar(pergunta);
    if (!APURACAO.matcher(texto).find()) {
      return List.of();
    }
    YearMonth atual = YearMonth.from(hoje);
    Optional<Periodo> periodo = resolver(pergunta, hoje);
    List<YearMonth> meses = new ArrayList<>();
    if (periodo.isPresent()) {
      YearMonth ultimo = YearMonth.from(periodo.get().fim());
      for (YearMonth m = YearMonth.from(periodo.get().inicio());
          !m.isAfter(ultimo) && !m.isAfter(atual); m = m.plusMonths(1)) {
        meses.add(m);
      }
    } else if (expressoesRelativas(texto) == 0 && !SUBPERIODO.matcher(texto).find()
        && !ABERTO.matcher(texto).find()) {
      meses.addAll(PeriodoAberto.mesesNomeados(texto, hoje));
      meses.sort(null);
    }
    return meses.size() > MAXIMO_MESES_APURACAO ? List.of() : List.copyOf(meses);
  }

  /** O texto com as expressões relativas trocadas por {@code #}, para ver o que sobra. */
  private static String semExpressoesRelativas(String texto) {
    char[] semAsLidas = texto.toCharArray();
    for (Regra regra : REGRAS) {
      Matcher m = regra.padrao().matcher(texto);
      while (m.find()) {
        Arrays.fill(semAsLidas, m.start(), m.end(), '#');
      }
    }
    return new String(semAsLidas);
  }

  /** Quantas expressões relativas ("mês passado", "este ano"...) a pergunta normalizada tem. */
  static int expressoesRelativas(String texto) {
    int n = 0;
    for (Regra regra : REGRAS) {
      Matcher m = regra.padrao().matcher(texto);
      while (m.find()) {
        n++;
      }
    }
    return n;
  }

  /** O período com o que depende de hoje e do texto: fim dos dados, mês futuro e mês corrente. */
  private static Periodo completo(Periodo periodo, LocalDate hoje, String texto) {
    boolean mesFuturo = periodo.mes() != null && periodo.mes().isAfter(YearMonth.from(hoje));
    boolean emCurso = !periodo.inicio().isAfter(hoje) && periodo.fim().isAfter(hoje);
    int diasAteOFimDoMes = YearMonth.from(hoje).equals(periodo.mes())
        ? (int) ChronoUnit.DAYS.between(hoje, periodo.mes().atEndOfMonth()) : -1;
    return new Periodo(periodo.inicio(), periodo.fim(), periodo.mes(),
        emCurso ? hoje : periodo.fim(),
        mesFuturo && !GUIAS_A_PARTIR_DE_HOJE.matcher(texto).find(), diasAteOFimDoMes);
  }

  /**
   * Os parâmetros de período de uma função com o período da pergunta. Função de datas recebe
   * {@code dataInicio}/{@code dataFim} (o fim nunca depois de hoje num período em curso:
   * {@link Periodo#fimDosDados()}); função de mês recebe {@code mes} só quando o período é um
   * mês do calendário (trimestre ou janela móvel não cabem num mês: fica o que o modelo pôs).
   *
   * @param funcao     nome da função
   * @param argumentos argumentos que o modelo escolheu (alterados no lugar)
   * @param periodo    período da pergunta
   * @return {@code true} se algum parâmetro mudou
   */
  public static boolean aplicar(String funcao, Map<String, Object> argumentos, Periodo periodo) {
    if (FUNCOES_DE_DATAS.contains(funcao)) {
      Object antesInicio = argumentos.put("dataInicio", periodo.inicio().toString());
      Object antesFim = argumentos.put("dataFim", periodo.fimDosDados().toString());
      return !periodo.inicio().toString().equals(antesInicio)
          || !periodo.fimDosDados().toString().equals(antesFim);
    }
    if (FUNCOES_DE_MES.contains(funcao) && periodo.mes() != null) {
      return !periodo.mes().toString().equals(argumentos.put("mes", periodo.mes().toString()));
    }
    return false;
  }

  /** A normalização da busca (sem acento, minúsculas), com a pontuação virando espaço. */
  static String normalizar(String pergunta) {
    return NormalizacaoBusca.normalizar(pergunta).replaceAll("[^a-z0-9/ ]+", " ")
        .replaceAll("\\s+", " ").strip();
  }

  /** Zero ("últimos 0 dias") ou acima do teto das funções: não resolve. */
  private static boolean foraDoLimite(String valor, int maximo) {
    int n = quantidade(valor);
    return n < 1 || n > maximo;
  }

  private static int quantidade(String valor) {
    return valor.chars().allMatch(Character::isDigit)
        ? Integer.parseInt(valor) : POR_EXTENSO.get(valor);
  }

  private static Periodo intervalo(LocalDate inicio, LocalDate fim) {
    return new Periodo(inicio, fim, null);
  }

  private static Periodo mes(LocalDate hoje, int deslocamento) {
    YearMonth mes = YearMonth.from(hoje).plusMonths(deslocamento);
    return new Periodo(mes.atDay(1), mes.atEndOfMonth(), mes);
  }

  /**
   * "Últimos N meses": com três, os três meses fechados (como o "último trimestre"); com outro N,
   * a janela móvel que termina hoje; zero ou acima do teto, não resolve. A ordem inversa ("três
   * últimos meses") só é lida com três: com outro N não foi decidida e fica com o modelo.
   */
  private static Periodo ultimosMeses(LocalDate hoje, String valor) {
    if (foraDoLimite(valor, MAXIMO_MESES)) {
      return null;
    }
    int n = quantidade(valor);
    return n == MESES_FECHADOS_DO_TRIMESTRE ? mesesFechados(hoje, n)
        : intervalo(hoje.minusMonths(n).plusDays(1), hoje);
  }

  /** Os {@code quantos} meses do calendário inteiros antes do mês corrente. */
  private static Periodo mesesFechados(LocalDate hoje, int quantos) {
    YearMonth atual = YearMonth.from(hoje);
    return intervalo(atual.minusMonths(quantos).atDay(1), atual.minusMonths(1).atEndOfMonth());
  }

  private static Periodo ano(LocalDate hoje, int deslocamento) {
    int ano = hoje.getYear() + deslocamento;
    return intervalo(LocalDate.of(ano, 1, 1), LocalDate.of(ano, 12, 31));
  }

  /** O trimestre do calendário de hoje, inteiro. */
  private static Periodo trimestre(LocalDate hoje) {
    LocalDate inicio = LocalDate.of(hoje.getYear(), 3 * ((hoje.getMonthValue() - 1) / 3) + 1, 1);
    return intervalo(inicio, inicio.plusMonths(3).minusDays(1));
  }

  private static Periodo semestre(LocalDate hoje, int deslocamento) {
    LocalDate inicio = LocalDate.of(hoje.getYear(), hoje.getMonthValue() <= 6 ? 1 : 7, 1)
        .plusMonths(6L * deslocamento);
    return intervalo(inicio, inicio.plusMonths(6).minusDays(1));
  }

  private static Regra fixa(String padrao, Function<LocalDate, Periodo> calculo) {
    return new Regra(Pattern.compile(padrao), (hoje, m, venc) -> calculo.apply(hoje));
  }

  /** Período em curso: até hoje, ou até o último dia em pergunta de vencimento. */
  private static Regra emCurso(String padrao, BiFunction<LocalDate, Boolean, Periodo> calculo) {
    return new Regra(Pattern.compile(padrao), (hoje, m, venc) -> calculo.apply(hoje, venc));
  }

  @FunctionalInterface
  private interface Calculo {
    /**
     * Período da expressão achada; {@code null} = expressão fora do limite (não resolve).
     *
     * @param vencimento a pergunta é de vencimento (o período em curso vai até o último dia)
     */
    Periodo calcular(LocalDate hoje, Matcher achado, boolean vencimento);
  }

  private record Regra(Pattern padrao, Calculo calculo) {}
}
