package br.com.agrocontabil.assistente;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
 * várias que dão o mesmo período) e nenhuma data explícita (mês por extenso, ano, dia/mês): com
 * "julho" ou "2025" na pergunta, ou com dois períodos diferentes, quem preenche é o modelo, como
 * antes. "Esta safra" não entra: a safra não tem regra de datas no sistema.
 *
 * <ul>
 *   <li>Mês e ano corrente vão do primeiro dia até hoje; mês, trimestre, semestre e ano passados
 *       são os do calendário, inteiros.</li>
 *   <li>"Últimos N meses" e "últimos N dias" são a janela móvel que termina hoje.</li>
 * </ul>
 */
public final class PeriodoDaPergunta {

  /** Funções que recebem {@code dataInicio}/{@code dataFim}. */
  public static final Set<String> FUNCOES_DE_DATAS = Set.of("totalComprasPorInsumo",
      "totalVendasPorProduto", "consultarCreditos", "consultarRetencoes", "listarNotas");

  /** Funções que recebem um mês ({@code mes}, AAAA-MM). */
  public static final Set<String> FUNCOES_DE_MES = Set.of("consultarApuracao", "vencimentosDoMes");

  /** Maior janela móvel aceita: a das funções de dados é de 24 meses. */
  static final int MAXIMO_MESES = 24;
  static final int MAXIMO_DIAS = 731;

  private static final Pattern ACENTOS = Pattern.compile("\\p{M}+");

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

  /** Data explícita: com ela, o período não é relativo e quem preenche é o modelo. */
  private static final Pattern DATA_EXPLICITA = Pattern.compile("\\b(janeiro|fevereiro|marco"
      + "|abril|maio|junho|julho|agosto|setembro|outubro|novembro|dezembro|(19|20)\\d{2}"
      + "|\\d{1,2}/\\d{1,2})\\b");

  /** Expressão relativa → período, na ordem de prioridade de leitura. */
  private static final List<Regra> REGRAS = List.of(
      fixa("\\bmes (?:retrasado)\\b|\\bpenultimo mes\\b", h -> mes(h, -2)),
      fixa("\\bmes (?:passado|anterior)\\b|\\bultimo mes\\b|\\bmes que passou\\b",
          h -> mes(h, -1)),
      fixa("\\bmes que vem\\b|\\bproximo mes\\b|\\bmes seguinte\\b", h -> mes(h, 1)),
      fixa("\\b" + ESTE + " mes\\b|\\bmes (?:atual|corrente)\\b", h -> mesAteHoje(h)),
      fixa("\\bano retrasado\\b", h -> ano(h, -2)),
      fixa("\\bano (?:passado|anterior)\\b", h -> ano(h, -1)),
      fixa("\\b" + ESTE + " ano\\b|\\bano (?:atual|corrente)\\b",
          h -> intervalo(h.withDayOfYear(1), h)),
      fixa("\\bultimo trimestre\\b|\\btrimestre (?:passado|anterior)\\b",
          h -> trimestre(h, -1)),
      fixa("\\b" + ESTE + " trimestre\\b|\\btrimestre (?:atual|corrente)\\b",
          h -> intervalo(trimestre(h, 0).inicio(), h)),
      fixa("\\bultimo semestre\\b|\\bsemestre (?:passado|anterior)\\b", h -> semestre(h, -1)),
      fixa("\\b" + ESTE + " semestre\\b|\\bsemestre (?:atual|corrente)\\b",
          h -> intervalo(semestre(h, 0).inicio(), h)),
      new Regra(Pattern.compile("\\bultimos " + QUANTIDADE + " meses\\b"),
          (h, m) -> quantidade(m.group(1)) > MAXIMO_MESES ? null
              : intervalo(h.minusMonths(quantidade(m.group(1))).plusDays(1), h)),
      new Regra(Pattern.compile("\\bultimos " + QUANTIDADE + " dias\\b"),
          (h, m) -> quantidade(m.group(1)) > MAXIMO_DIAS ? null
              : intervalo(h.minusDays(quantidade(m.group(1)) - 1L), h)));

  private PeriodoDaPergunta() {}

  /**
   * Período da pergunta.
   *
   * @param inicio primeiro dia
   * @param fim    último dia
   * @param mes    o mês, quando o período é um mês do calendário (passado, corrente ou futuro);
   *               {@code null} nos demais
   */
  public record Periodo(LocalDate inicio, LocalDate fim, YearMonth mes) {

    /** Linha para o modelo, antes da pergunta: as datas já calculadas. */
    public String paraOModelo() {
      return "Periodo da pergunta, ja calculado: de " + inicio + " a " + fim
          + (mes == null ? "" : " (mes " + mes + ")") + ".";
    }
  }

  /**
   * O período relativo da pergunta, se houver um só e nenhuma data explícita.
   *
   * @param pergunta pergunta do usuário
   * @param hoje     data de referência (Brasília)
   * @return o período, ou vazio
   */
  public static Optional<Periodo> resolver(String pergunta, LocalDate hoje) {
    String texto = normalizar(pergunta);
    if (texto.isEmpty() || DATA_EXPLICITA.matcher(texto).find()) {
      return Optional.empty();
    }
    Set<Periodo> achados = new LinkedHashSet<>();
    for (Regra regra : REGRAS) {
      Matcher m = regra.padrao().matcher(texto);
      while (m.find()) {
        Periodo p = regra.calculo().calcular(hoje, m);
        if (p == null) {
          return Optional.empty();
        }
        achados.add(p);
      }
    }
    return achados.size() == 1 ? Optional.of(achados.iterator().next()) : Optional.empty();
  }

  /**
   * Os parâmetros de período de uma função com o período da pergunta. Função de datas recebe
   * {@code dataInicio}/{@code dataFim}; função de mês recebe {@code mes} só quando o período é um
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
      Object antesFim = argumentos.put("dataFim", periodo.fim().toString());
      return !periodo.inicio().toString().equals(antesInicio)
          || !periodo.fim().toString().equals(antesFim);
    }
    if (FUNCOES_DE_MES.contains(funcao) && periodo.mes() != null) {
      return !periodo.mes().toString().equals(argumentos.put("mes", periodo.mes().toString()));
    }
    return false;
  }

  static String normalizar(String pergunta) {
    if (pergunta == null) {
      return "";
    }
    String semAcento = ACENTOS.matcher(Normalizer.normalize(pergunta, Normalizer.Form.NFD))
        .replaceAll("").toLowerCase(Locale.ROOT);
    return semAcento.replaceAll("[^a-z0-9/ ]+", " ").replaceAll("\\s+", " ").strip();
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

  private static Periodo mesAteHoje(LocalDate hoje) {
    return new Periodo(hoje.withDayOfMonth(1), hoje, YearMonth.from(hoje));
  }

  private static Periodo ano(LocalDate hoje, int deslocamento) {
    int ano = hoje.getYear() + deslocamento;
    return intervalo(LocalDate.of(ano, 1, 1), LocalDate.of(ano, 12, 31));
  }

  private static Periodo trimestre(LocalDate hoje, int deslocamento) {
    LocalDate inicio = LocalDate.of(hoje.getYear(), 3 * ((hoje.getMonthValue() - 1) / 3) + 1, 1)
        .plusMonths(3L * deslocamento);
    return intervalo(inicio, inicio.plusMonths(3).minusDays(1));
  }

  private static Periodo semestre(LocalDate hoje, int deslocamento) {
    LocalDate inicio = LocalDate.of(hoje.getYear(), hoje.getMonthValue() <= 6 ? 1 : 7, 1)
        .plusMonths(6L * deslocamento);
    return intervalo(inicio, inicio.plusMonths(6).minusDays(1));
  }

  private static Regra fixa(String padrao, Function<LocalDate, Periodo> calculo) {
    return new Regra(Pattern.compile(padrao), (hoje, m) -> calculo.apply(hoje));
  }

  @FunctionalInterface
  private interface Calculo {
    /** Período da expressão achada; {@code null} = expressão fora do limite (não resolve). */
    Periodo calcular(LocalDate hoje, Matcher achado);
  }

  private record Regra(Pattern padrao, Calculo calculo) {}
}
