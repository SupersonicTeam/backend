package br.com.agrocontabil.assistente;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.assistente.PeriodoDaPergunta.Periodo;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Período relativo da pergunta resolvido pelo backend (issue #442), com frases inventadas. */
class PeriodoDaPerguntaTest {

  private static final LocalDate HOJE = LocalDate.of(2027, 2, 17);

  @ParameterizedTest(name = "{0}")
  @CsvSource(delimiter = '|', value = {
      "Qual foi o gasto com calcário no mês passado?|2027-01-01|2027-01-31|2027-01",
      "E o mês anterior, quanto saiu de sorgo?|2027-01-01|2027-01-31|2027-01",
      "Quanto de crédito apareceu no mês retrasado?|2026-12-01|2026-12-31|2026-12",
      "Como fica a apuração deste mês?|2027-02-01|2027-02-17|2027-02",
      "As vendas de girassol neste mês já fecharam?|2027-02-01|2027-02-17|2027-02",
      "Quanto vou pagar de guia no mês que vem?|2027-03-01|2027-03-31|2027-03",
      "Quanto vendi de cevada no ano passado?|2026-01-01|2026-12-31|",
      "Os créditos deste ano somam quanto?|2027-01-01|2027-02-17|",
      "Me fala as retenções do ano retrasado|2025-01-01|2025-12-31|",
      "Quanto comprei de ureia no último trimestre?|2026-10-01|2026-12-31|",
      "E neste trimestre, já teve compra de sementes?|2027-01-01|2027-02-17|",
      "No semestre passado vendi quanto de feijão?|2026-07-01|2026-12-31|",
      "Quanto de nota emiti nos últimos quatro meses?|2026-10-18|2027-02-17|",
      "Compras de diesel nos últimos 45 dias|2027-01-04|2027-02-17|"})
  @DisplayName("expressão relativa vira o período certo, a partir de hoje")
  void resolve(String pergunta, String inicio, String fim, String mes) {
    Periodo p = PeriodoDaPergunta.resolver(pergunta, HOJE).orElseThrow();

    assertThat(p.inicio()).isEqualTo(inicio);
    assertThat(p.fim()).isEqualTo(fim);
    assertThat(p.mes()).isEqualTo(mes == null ? null : YearMonth.parse(mes));
  }

  @Test
  @DisplayName("virada de ano: mês passado em janeiro é dezembro do ano anterior")
  void viradaDeAno() {
    Periodo p = PeriodoDaPergunta.resolver("o débito do mês passado", LocalDate.of(2027, 1, 9))
        .orElseThrow();

    assertThat(p.mes()).isEqualTo(YearMonth.of(2026, 12));
    assertThat(p.fim()).isEqualTo("2026-12-31");
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @ValueSource(strings = {
      "Quanto vendi de cevada em março de 2026?",
      "Compras de ureia do mês passado e de 2025",
      "Quanto saiu de crédito em 12/2026, o mês passado?",
      "Compare este mês com o mês passado",
      "Quanto vendi de girassol?",
      "Quanto comprei de diesel nos últimos 40 meses?",
      "Quanto vendi nesta safra?",
      ""})
  @DisplayName("data explícita, dois períodos, sem período ou fora do limite: não resolve")
  void naoResolve(String pergunta) {
    assertThat(PeriodoDaPergunta.resolver(pergunta, HOJE)).isEmpty();
  }

  @Test
  @DisplayName("duas expressões com o mesmo período contam como uma")
  void mesmoPeriodoDuasVezes() {
    assertThat(PeriodoDaPergunta.resolver("mês passado, isto é, o mês anterior", HOJE))
        .map(Periodo::mes).contains(YearMonth.of(2027, 1));
  }

  @Test
  @DisplayName("função de datas recebe as datas; de mês, o mês; as outras não mudam")
  void aplicar() {
    Periodo mes = PeriodoDaPergunta.resolver("no mês passado", HOJE).orElseThrow();
    Periodo trimestre = PeriodoDaPergunta.resolver("no último trimestre", HOJE).orElseThrow();

    Map<String, Object> compras = new LinkedHashMap<>(Map.of("descricaoOuNcm", "ureia",
        "dataInicio", "2026-11-01", "dataFim", "2027-01-31"));
    assertThat(PeriodoDaPergunta.aplicar("totalComprasPorInsumo", compras, mes)).isTrue();
    assertThat(compras).containsEntry("dataInicio", "2027-01-01")
        .containsEntry("dataFim", "2027-01-31").containsEntry("descricaoOuNcm", "ureia");

    Map<String, Object> notas = new LinkedHashMap<>(Map.of("tipo", "SAIDA"));
    assertThat(PeriodoDaPergunta.aplicar("listarNotas", notas, trimestre)).isTrue();
    assertThat(notas).containsEntry("dataInicio", "2026-10-01")
        .containsEntry("dataFim", "2026-12-31").containsEntry("tipo", "SAIDA");

    Map<String, Object> apuracao = new LinkedHashMap<>(Map.of("mes", "2027-02"));
    assertThat(PeriodoDaPergunta.aplicar("consultarApuracao", apuracao, mes)).isTrue();
    assertThat(apuracao).containsEntry("mes", "2027-01");

    Map<String, Object> apuracaoTrimestre = new LinkedHashMap<>(Map.of("mes", "2026-12"));
    assertThat(PeriodoDaPergunta.aplicar("consultarApuracao", apuracaoTrimestre, trimestre))
        .isFalse();
    assertThat(apuracaoTrimestre).containsEntry("mes", "2026-12");

    Map<String, Object> vencimentos = new LinkedHashMap<>(Map.of("dias", 30));
    assertThat(PeriodoDaPergunta.aplicar("proximosVencimentos", vencimentos, mes)).isFalse();
    assertThat(vencimentos).containsOnlyKeys("dias");

    Map<String, Object> jaCerto = new LinkedHashMap<>(Map.of("mes", "2027-01"));
    assertThat(PeriodoDaPergunta.aplicar("vencimentosDoMes", jaCerto, mes)).isFalse();
  }

  @Test
  @DisplayName("linha para o modelo traz as datas e o mês quando há")
  void paraOModelo() {
    assertThat(PeriodoDaPergunta.resolver("mês que vem", HOJE).orElseThrow().paraOModelo())
        .isEqualTo("Periodo da pergunta, ja calculado: de 2027-03-01 a 2027-03-31 (mes 2027-03).");
    assertThat(PeriodoDaPergunta.resolver("ano passado", HOJE).orElseThrow().paraOModelo())
        .isEqualTo("Periodo da pergunta, ja calculado: de 2026-01-01 a 2026-12-31.");
  }
}
