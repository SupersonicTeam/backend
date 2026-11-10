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
      "Quanto comprei de ureia no último trimestre?|2026-11-01|2027-01-31|",
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
      "Quanto comprei de sorgo no primeiro trimestre do ano passado?",
      "E na primeira quinzena do mês passado?",
      "Vendi mais que no mesmo mês do ano passado?",
      "Quanto saiu de cevada no fim do mês passado?",
      "Compras dos últimos 0 dias",
      ""})
  @DisplayName("data explícita, dois períodos, sem período ou fora do limite: não resolve")
  void naoResolve(String pergunta) {
    assertThat(PeriodoDaPergunta.resolver(pergunta, HOJE)).isEmpty();
  }

  @Test
  @DisplayName("número de nota não é ano: o mês passado ainda resolve")
  void numeroDeNotaNaoEAno() {
    assertThat(PeriodoDaPergunta.resolver("A nota 2045 do mês passado saiu?", HOJE))
        .map(Periodo::mes).contains(YearMonth.of(2027, 1));
  }

  @Test
  @DisplayName("as listas de funções batem com os parâmetros das @Tool de dados")
  void listasBatemComAsFuncoes() {
    java.util.Set<String> deDatas = new java.util.HashSet<>();
    java.util.Set<String> deMes = new java.util.HashSet<>();
    for (java.lang.reflect.Method m :
        br.com.agrocontabil.assistente.ferramentas.FerramentasDadosProdutor.class.getMethods()) {
      org.springframework.ai.tool.annotation.Tool tool =
          m.getAnnotation(org.springframework.ai.tool.annotation.Tool.class);
      if (tool == null) {
        continue;
      }
      java.util.List<String> nomes = java.util.Arrays.stream(m.getParameters())
          .map(java.lang.reflect.Parameter::getName).toList();
      if (nomes.contains("dataInicio") && nomes.contains("dataFim")) {
        deDatas.add(tool.name());
      }
      if (nomes.contains("mes")) {
        deMes.add(tool.name());
      }
    }
    assertThat(PeriodoDaPergunta.FUNCOES_DE_DATAS).isEqualTo(deDatas);
    assertThat(PeriodoDaPergunta.FUNCOES_DE_MES).isEqualTo(deMes);
  }

  @Test
  @DisplayName("as funções da troca do item 1 existem com esses nomes (#452)")
  void funcoesDaTrocaExistem() {
    java.util.Set<String> nomes = java.util.Arrays.stream(
            br.com.agrocontabil.assistente.ferramentas.FerramentasDadosProdutor.class.getMethods())
        .map(m -> m.getAnnotation(org.springframework.ai.tool.annotation.Tool.class))
        .filter(java.util.Objects::nonNull)
        .map(org.springframework.ai.tool.annotation.Tool::name)
        .collect(java.util.stream.Collectors.toSet());

    assertThat(nomes).contains(PeriodoDaPergunta.FUNCAO_PROXIMOS_VENCIMENTOS,
        PeriodoDaPergunta.FUNCAO_VENCIMENTOS_DO_MES);
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
    assertThat(notas).containsEntry("dataInicio", "2026-11-01")
        .containsEntry("dataFim", "2027-01-31").containsEntry("tipo", "SAIDA");

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

  @ParameterizedTest(name = "[{index}] hoje {0}: {1}")
  @CsvSource(delimiter = '|', value = {
      "2026-11-09|Qual o total de crédito no último trimestre?|2026-08-01|2026-10-31",
      "2026-12-03|Retenções do trimestre passado, por favor|2026-09-01|2026-11-30",
      "2027-01-15|E no trimestre anterior, quanto entrou de girassol?|2026-10-01|2026-12-31",
      "2026-11-30|Notas de saída do último trimestre|2026-08-01|2026-10-31"})
  @DisplayName("último trimestre são os três últimos meses fechados (#452, item 4)")
  void ultimoTrimestreSaoMesesFechados(String hoje, String pergunta, String inicio, String fim) {
    Periodo p = PeriodoDaPergunta.resolver(pergunta, LocalDate.parse(hoje)).orElseThrow();

    assertThat(p.inicio()).isEqualTo(inicio);
    assertThat(p.fim()).isEqualTo(fim);
    assertThat(p.mes()).isNull();
  }

  @ParameterizedTest(name = "[{index}] hoje {0}: {1}")
  @CsvSource(delimiter = '|', value = {
      "2026-11-09|Compras de sorgo nos últimos três meses|2026-08-01|2026-10-31",
      "2026-12-03|Quanto entrou de retenção nos últimos 3 meses?|2026-09-01|2026-11-30",
      "2027-01-02|Notas de saída dos últimos tres meses|2026-10-01|2026-12-31",
      "2026-11-09|Créditos nos últimos 3 meses, isto é, no último trimestre|2026-08-01|2026-10-31",
      "2026-12-01|Quanto vendi de aveia nos três últimos meses?|2026-09-01|2026-11-30",
      "2027-03-31|Retenções dos 3 últimos meses|2026-12-01|2027-02-28"})
  @DisplayName("últimos três meses são os três meses fechados, como o trimestre (#452)")
  void ultimosTresMesesSaoMesesFechados(String hoje, String pergunta, String inicio,
      String fim) {
    Periodo p = PeriodoDaPergunta.resolver(pergunta, LocalDate.parse(hoje)).orElseThrow();

    assertThat(p.inicio()).isEqualTo(inicio);
    assertThat(p.fim()).isEqualTo(fim);
    assertThat(p.fimDosDados()).isEqualTo(fim);
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @CsvSource(delimiter = '|', value = {
      "Compras de adubo nos últimos dois meses|2026-09-10|2026-11-09",
      "Vendas de aveia nos últimos 4 meses|2026-07-10|2026-11-09",
      "Notas dos últimos 90 dias|2026-08-12|2026-11-09"})
  @DisplayName("os demais últimos N meses e N dias seguem até hoje (não decididos)")
  void demaisJanelasSeguemAteHoje(String pergunta, String inicio, String fim) {
    Periodo p = PeriodoDaPergunta.resolver(pergunta, LocalDate.of(2026, 11, 9)).orElseThrow();

    assertThat(p.inicio()).isEqualTo(inicio);
    assertThat(p.fim()).isEqualTo(fim);
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(delimiter = '|', value = {
      "Que boletos de imposto vencem neste mês?|2027-02-01|2027-02-28|2027-02",
      "Quanto tenho a pagar de tributo no mês atual?|2027-02-01|2027-02-28|2027-02",
      "Os prazos de recolhimento deste ano|2027-01-01|2027-12-31|",
      "Alguma guia cai neste trimestre?|2027-01-01|2027-03-31|",
      "E os DARFs deste semestre?|2027-01-01|2027-06-30|",
      "Quanto de imposto vou recolher neste mês?|2027-02-01|2027-02-28|2027-02",
      "Tenho débito para quitar no mês corrente?|2027-02-01|2027-02-28|2027-02"})
  @DisplayName("período em curso de vencimento vai até o último dia (#452, item 2)")
  void emCursoDeVencimentoVaiAteOFim(String pergunta, String inicio, String fim, String mes) {
    Periodo p = PeriodoDaPergunta.resolver(pergunta, HOJE).orElseThrow();

    assertThat(p.inicio()).isEqualTo(inicio);
    assertThat(p.fim()).isEqualTo(fim);
    assertThat(p.mes()).isEqualTo(mes == null ? null : YearMonth.parse(mes));
    assertThat(p.fimDosDados()).isEqualTo(HOJE);
  }

  @Test
  @DisplayName("pergunta mista: as funções de datas seguem até hoje (#452, item 2)")
  void funcoesDeDatasSeguemAteHoje() {
    Periodo p = PeriodoDaPergunta
        .resolver("Quanto vendi de aveia neste mês e quanto falta pagar?", HOJE).orElseThrow();

    Map<String, Object> vendas = new LinkedHashMap<>(Map.of("descricaoOuNcm", "aveia"));
    assertThat(PeriodoDaPergunta.aplicar("totalVendasPorProduto", vendas, p)).isTrue();
    assertThat(vendas).containsEntry("dataInicio", "2027-02-01")
        .containsEntry("dataFim", "2027-02-17");
    assertThat(p.paraOModelo()).contains("de 2027-02-01 a 2027-02-28 (mes 2027-02)");
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @ValueSource(strings = {
      "Quanto vendi de sorgo este ano até agora?",
      "Até hoje, quanto entrou de crédito neste ano?",
      "Neste ano, até o momento, quanto saiu de retenção?"})
  @DisplayName("até hoje, até agora e até o momento são o período em curso: resolve")
  void ateHojeResolve(String pergunta) {
    Periodo p = PeriodoDaPergunta.resolver(pergunta, HOJE).orElseThrow();

    assertThat(p.inicio()).isEqualTo("2027-01-01");
    assertThat(p.fim()).isEqualTo(HOJE);
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @CsvSource(delimiter = '|', value = {
      "Que guias tenho para quitar no próximo mês?|true",
      "Quanto de DARF cai no mês seguinte?|true",
      "Quanto de cevada vou entregar no mês que vem?|true",
      "Das guias do mês que vem, quais caem nos próximos 10 dias?|false",
      "Bom dia! Quais guias vencem no mês que vem?|true",
      "Vou entregar cevada no mês que vem; quando vence a minha próxima guia?|false",
      "Para o mês seguinte, qual o próximo vencimento?|false",
      "Que guias vencem neste mês?|false",
      "Ficou guia sem pagar no mês passado?|false"})
  @DisplayName("guias de mês futuro marcam a troca de proximosVencimentos (#452, item 1)")
  void marcaMesFuturo(String pergunta, boolean esperado) {
    assertThat(PeriodoDaPergunta.resolver(pergunta, HOJE).orElseThrow()
        .vencimentosDoMesFuturo()).isEqualTo(esperado);
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @ValueSource(strings = {
      "Quanto faturei na safra do ano passado?",
      "Retenções no mês passado e no retrasado",
      "Quanto tenho de guia neste mês e no próximo?",
      "Compare o ano passado com o retrasado",
      "Esse imposto vence no mês seguinte ao da entrega?",
      "Compras de calcário nos seis últimos meses",
      "Créditos do ano passado pra frente",
      "Vendas do mês passado, o retrasado também",
      "Guias deste mês e também para o próximo",
      "O recolhimento é no mês anterior ao embarque?",
      "Vendas de girassol de março até o mês passado",
      "Notas até 15/01",
      "Compras de sorgo desde o mês passado e o retrasado",
      "Vendas até dezembro",
      "Safra 26/28 de milho",
      "Na safra 25/26, quanto vendi no mês passado?",
      "Notas da safra 27/28",
      "Vendas do ano retrasado pra cá",
      "Créditos desde 2024"})
  @DisplayName("safra sem número, dois períodos, regra e âncora ambígua: não resolve (#452)")
  void naoResolveAbertoOuRegra(String pergunta) {
    assertThat(PeriodoDaPergunta.resolver(pergunta, HOJE)).isEmpty();
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @CsvSource(delimiter = '|', value = {
      "Desde o mês passado, quanto entrou de girassol?|2027-01-01|2027-02-17|2027-02-17",
      "A partir do ano passado, quanto deu de crédito?|2026-01-01|2027-02-17|2027-02-17",
      "Quanto vendi de feijão do ano passado pra cá?|2026-01-01|2027-02-17|2027-02-17",
      "Notas do mês retrasado pra cá|2026-12-01|2027-02-17|2027-02-17",
      "Compras do mês passado em diante|2027-01-01|2027-02-17|2027-02-17",
      "Desde janeiro, quanto saiu de retenção?|2027-01-01|2027-02-17|2027-02-17",
      "Vendas desde outubro de 2026|2026-10-01|2027-02-17|2027-02-17",
      "Vendi quanto de sorgo até o mês passado?|2025-02-01|2027-01-31|2027-01-31",
      "Créditos até janeiro|2025-02-01|2027-01-31|2027-01-31",
      "Quero as notas de dezembro de 2026 pra trás|2025-01-01|2026-12-31|2026-12-31",
      "Quero as notas do mês passado para trás|2025-02-01|2027-01-31|2027-01-31",
      "Teve compra de calcário antes do mês passado?|2025-01-01|2026-12-31|2026-12-31",
      "Vendas de aveia depois do mês retrasado|2027-01-01|2027-02-17|2027-02-17",
      "Compras de adubo após o ano retrasado|2026-01-01|2027-02-17|2027-02-17",
      "Retenções até o ano passado|2025-01-01|2026-12-31|2026-12-31",
      "Quanto vendi na safra 26/27?|2026-07-01|2027-06-30|2027-02-17",
      "Quanto vendi na safra 2025/2026?|2025-07-01|2026-06-30|2026-06-30",
      "Compras da safra 2025/26|2025-07-01|2026-06-30|2026-06-30",
      "Quanto vendi na safra de soja 25/26?|2025-07-01|2026-06-30|2026-06-30",
      "Compras da safra 25-26|2025-07-01|2026-06-30|2026-06-30",
      "Vendas da safra 2025 a 2026|2025-07-01|2026-06-30|2026-06-30",
      "Vendas acima de 2000 reais desde o mês passado|2027-01-01|2027-02-17|2027-02-17"})
  @DisplayName("período aberto, contado de um ponto e safra numerada (#452, rodada 2)")
  void resolveAbertoESafra(String pergunta, String inicio, String fim, String fimDosDados) {
    Periodo p = PeriodoDaPergunta.resolver(pergunta, HOJE).orElseThrow();

    assertThat(p.inicio()).isEqualTo(inicio);
    assertThat(p.fim()).isEqualTo(fim);
    assertThat(p.fimDosDados()).isEqualTo(fimDosDados);
    assertThat(p.mes()).isNull();
  }

  @Test
  @DisplayName("início aberto cabe no limite de 24 meses das funções")
  void inicioAbertoCabeNoLimite() {
    Periodo p = PeriodoDaPergunta.resolver("Vendas até o mês passado", HOJE).orElseThrow();

    assertThat(java.time.temporal.ChronoUnit.DAYS.between(p.inicio(), p.fimDosDados()))
        .isLessThanOrEqualTo(br.com.agrocontabil.assistente.ferramentas
            .FerramentasDadosProdutor.MAXIMO_DIAS_PERIODO);
  }

  @ParameterizedTest(name = "[{index}] hoje {0}: {1}")
  @CsvSource(delimiter = '|', value = {
      "2026-11-09|Quais guias de Funrural vencem neste mês?|21",
      "2026-11-30|O que vence neste mês?|0",
      "2026-11-09|Vendas deste mês|21",
      "2026-11-09|Guias do mês passado|-1",
      "2026-11-09|Guias do mês que vem|-1"})
  @DisplayName("mês corrente traz os dias que faltam até o fim dele (#452, rodada 2)")
  void diasAteOFimDoMes(String hoje, String pergunta, int dias) {
    assertThat(PeriodoDaPergunta.resolver(pergunta, LocalDate.parse(hoje)).orElseThrow()
        .diasAteOFimDoMes()).isEqualTo(dias);
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @CsvSource(delimiter = '|', value = {
      "Como ficou a apuração do último trimestre?|2026-08,2026-09,2026-10",
      "Me mostra a apuração de setembro|2026-09",
      "Apuração de agosto e de setembro de 2026|2026-08,2026-09",
      "Qual o saldo de IBS do mês passado?|2026-10",
      "A apuração deste mês já fechou?|2026-11",
      "Apuração de março de 2025|2025-03"})
  @DisplayName("meses da apuração sem função escolhida (#452, rodada 2)")
  void mesesDaApuracao(String pergunta, String meses) {
    assertThat(PeriodoDaPergunta.mesesDaApuracao(pergunta, LocalDate.of(2026, 11, 9)))
        .extracting(YearMonth::toString)
        .containsExactly(meses.split(","));
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @ValueSource(strings = {
      "Quanto vendi de soja no último trimestre?",
      "Como funciona a apuração do IBS?",
      "Apuração de dezembro",
      "Apuração do mês que vem",
      "Apuração dos últimos 18 meses",
      "Apuração de setembro até o mês passado"})
  @DisplayName("sem apuração, sem período, mês ambíguo ou futuro e mais de 12 meses: nada")
  void semMesesDaApuracao(String pergunta) {
    assertThat(PeriodoDaPergunta.mesesDaApuracao(pergunta, LocalDate.of(2026, 11, 9)))
        .isEmpty();
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @ValueSource(strings = {
      "Como ficou a apuração do mês passado?",
      "Me diz a apuração do mês anterior",
      "Quanto vendi de soja no mês passado, e de milho?"})
  @DisplayName("apuração do mês passado segue resolvendo (passo 7 do roteiro)")
  void apuracaoDoMesPassadoSegueResolvendo(String pergunta) {
    assertThat(PeriodoDaPergunta.resolver(pergunta, HOJE)).map(Periodo::mes)
        .contains(YearMonth.of(2027, 1));
  }

  @ParameterizedTest(name = "[{index}] {0} -> aberto {1}")
  @CsvSource(delimiter = '|', value = {
      "Retenções de janeiro pra trás|true",
      "Compras desde o ano passado|true",
      "Vendas da safra 25/26|true",
      "Vendas do mês passado|false",
      "Notas do último trimestre|false"})
  @DisplayName("período aberto não vai ao modelo; o fechado vai (#452, rodada 3)")
  void abertoNaoVaiAoModelo(String pergunta, boolean aberto) {
    Periodo p = PeriodoDaPergunta.resolver(pergunta, HOJE).orElseThrow();

    assertThat(p.aberto()).isEqualTo(aberto);
    assertThat(p.visivelAoModelo().isPresent()).isEqualTo(!aberto);
  }

  @Test
  @DisplayName("comparação entre dois meses não vira período: as datas ficam com o modelo")
  void comparacaoEntreMesesNaoResolve() {
    assertThat(PeriodoDaPergunta.resolver("Vendas de julho contra junho", HOJE)).isEmpty();
    assertThat(PeriodoDaPergunta.resolver("Retenções de janeiro até fevereiro", HOJE)).isEmpty();
  }
}
