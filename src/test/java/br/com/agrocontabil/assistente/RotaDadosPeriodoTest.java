package br.com.agrocontabil.assistente;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.assistente.PeriodoDaPergunta.Periodo;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/** As chamadas do modelo com o período da pergunta (issue #442). */
class RotaDadosPeriodoTest {

  private static final Periodo SEMESTRE = PeriodoDaPergunta
      .resolver("no semestre passado", LocalDate.of(2026, 9, 2)).orElseThrow();

  private static ChatResponse chamadas(ToolCall... chamadas) {
    return new ChatResponse(List.of(new Generation(
        AssistantMessage.builder().content("").toolCalls(List.of(chamadas)).build())));
  }

  private static List<ToolCall> chamadasDe(ChatResponse resposta) {
    return resposta.getResults().get(0).getOutput().getToolCalls();
  }

  @Test
  @DisplayName("grava as datas, mantém id, nome e os outros parâmetros")
  void gravaAsDatas() {
    ChatResponse ajustada = RotaDados.comPeriodo(chamadas(
        new ToolCall("x1", "function", "consultarRetencoes",
            "{\"dataInicio\":\"2026-06-01\",\"dataFim\":\"2026-08-31\"}"),
        new ToolCall("x2", "function", "totalVendasPorProduto", "{\"descricaoOuNcm\":\"sorgo\"}")),
        SEMESTRE);

    List<ToolCall> resultado = chamadasDe(ajustada);
    assertThat(resultado).extracting(ToolCall::id).containsExactly("x1", "x2");
    assertThat(resultado.get(0).arguments())
        .isEqualTo("{\"dataInicio\":\"2026-01-01\",\"dataFim\":\"2026-06-30\"}");
    assertThat(resultado.get(1).arguments()).isEqualTo(
        "{\"descricaoOuNcm\":\"sorgo\",\"dataInicio\":\"2026-01-01\",\"dataFim\":\"2026-06-30\"}");
  }

  @Test
  @DisplayName("a mesma função duas vezes é comparação: as janelas do modelo ficam")
  void comparacaoFica() {
    ChatResponse original = chamadas(
        new ToolCall("z1", "function", "listarNotas",
            "{\"dataInicio\":\"2026-01-01\",\"dataFim\":\"2026-06-30\"}"),
        new ToolCall("z2", "function", "listarNotas",
            "{\"dataInicio\":\"2025-01-01\",\"dataFim\":\"2025-12-31\"}"));

    assertThat(RotaDados.comPeriodo(original, SEMESTRE)).isSameAs(original);
  }

  @Test
  @DisplayName("nada a mudar ou argumento que não é JSON: a resposta do modelo segue igual")
  void semMudanca() {
    ChatResponse original = chamadas(
        new ToolCall("y1", "function", "guiasEmAtraso", "{}"),
        new ToolCall("y2", "function", "consultarCreditos", "dataInicio=ontem"));

    assertThat(RotaDados.comPeriodo(original, SEMESTRE)).isSameAs(original);
  }

  private static final LocalDate HOJE = LocalDate.of(2026, 11, 20);

  private static Periodo periodo(String pergunta) {
    return PeriodoDaPergunta.resolver(pergunta, HOJE).orElseThrow();
  }

  @Test
  @DisplayName("guias do mês que vem: proximosVencimentos vira vencimentosDoMes (#452, item 1)")
  void proximosVencimentosViraVencimentosDoMes() {
    ChatResponse ajustada = RotaDados.comPeriodo(chamadas(
        new ToolCall("v1", "function", "proximosVencimentos", "{\"dias\":30}")),
        periodo("Quais guias tenho de recolher no mês que vem?"));

    List<ToolCall> resultado = chamadasDe(ajustada);
    assertThat(resultado).hasSize(1);
    assertThat(resultado.get(0).id()).isEqualTo("v1");
    assertThat(resultado.get(0).name()).isEqualTo("vencimentosDoMes");
    assertThat(resultado.get(0).arguments()).isEqualTo("{\"mes\":\"2026-12\"}");
  }

  @Test
  @DisplayName("com vencimentosDoMes já chamada, proximosVencimentos sai (#452, item 1)")
  void proximosVencimentosSaiQuandoJaHaVencimentosDoMes() {
    ChatResponse ajustada = RotaDados.comPeriodo(chamadas(
        new ToolCall("w1", "function", "proximosVencimentos", "{}"),
        new ToolCall("w2", "function", "vencimentosDoMes", "{\"mes\":\"2026-11\"}")),
        periodo("O que vence de guia no próximo mês?"));

    List<ToolCall> resultado = chamadasDe(ajustada);
    assertThat(resultado).extracting(ToolCall::id).containsExactly("w2");
    assertThat(resultado.get(0).arguments()).isEqualTo("{\"mes\":\"2026-12\"}");
  }

  @Test
  @DisplayName("vencimentosDoMes duas vezes: proximosVencimentos sai e as duas ficam (#452)")
  void vencimentosDoMesDuasVezes() {
    ChatResponse ajustada = RotaDados.comPeriodo(chamadas(
        new ToolCall("t1", "function", "vencimentosDoMes", "{\"mes\":\"2026-12\"}"),
        new ToolCall("t2", "function", "proximosVencimentos", "{}"),
        new ToolCall("t3", "function", "vencimentosDoMes", "{\"mes\":\"2027-01\"}")),
        periodo("Bom dia, que guias tenho no mês que vem?"));

    List<ToolCall> resultado = chamadasDe(ajustada);
    assertThat(resultado).extracting(ToolCall::id).containsExactly("t1", "t3");
    assertThat(resultado).extracting(ToolCall::arguments)
        .containsExactly("{\"mes\":\"2026-12\"}", "{\"mes\":\"2027-01\"}");
  }

  @Test
  @DisplayName("janela em dias de mês futuro: proximosVencimentos fica (#452, item 1)")
  void proximosVencimentosFica() {
    ChatResponse emDias = chamadas(
        new ToolCall("u2", "function", "proximosVencimentos", "{\"dias\":10}"));

    assertThat(RotaDados.comPeriodo(emDias,
        periodo("Do mês que vem, que guias caem nos próximos 10 dias?"))).isSameAs(emDias);
  }

  @Test
  @DisplayName("neste mês: a janela de proximosVencimentos para no último dia (#452, rodada 2)")
  void esteMesLimitaAJanela() {
    Periodo esteMes = periodo("Tenho guia de ICMS vencendo neste mês?");
    ChatResponse padrao = chamadas(
        new ToolCall("s1", "function", "proximosVencimentos", "{}"));
    ChatResponse longa = chamadas(
        new ToolCall("s2", "function", "proximosVencimentos", "{\"dias\":\"45\"}"));
    ChatResponse curta = chamadas(
        new ToolCall("s3", "function", "proximosVencimentos", "{\"dias\":5}"));

    assertThat(chamadasDe(RotaDados.comPeriodo(padrao, esteMes)).get(0).arguments())
        .isEqualTo("{\"dias\":10}");
    assertThat(chamadasDe(RotaDados.comPeriodo(longa, esteMes)).get(0).arguments())
        .isEqualTo("{\"dias\":10}");
    assertThat(RotaDados.comPeriodo(curta, esteMes)).isSameAs(curta);
  }

  @Test
  @DisplayName("último dia do mês: proximosVencimentos vira vencimentosDoMes (#452, rodada 2)")
  void ultimoDiaDoMes() {
    Periodo ultimoDia = PeriodoDaPergunta
        .resolver("O que vence nesse mês?", LocalDate.of(2026, 11, 30)).orElseThrow();

    List<ToolCall> resultado = chamadasDe(RotaDados.comPeriodo(chamadas(
        new ToolCall("r1", "function", "proximosVencimentos", "{}")), ultimoDia));
    assertThat(resultado).extracting(ToolCall::name).containsExactly("vencimentosDoMes");
    assertThat(resultado.get(0).arguments()).isEqualTo("{\"mes\":\"2026-11\"}");
  }

  @Test
  @DisplayName("apuração sem função escolhida: consultarApuracao de cada mês (#452, rodada 2)")
  void apuracaoSemFuncao() {
    List<ToolCall> resultado = chamadasDe(RotaDados.apuracaoSemFuncao(
        "Quero ver a apuração do último trimestre", HOJE).orElseThrow());

    assertThat(resultado).extracting(ToolCall::name).containsOnly("consultarApuracao");
    assertThat(resultado).extracting(ToolCall::arguments).containsExactly(
        "{\"mes\":\"2026-08\"}", "{\"mes\":\"2026-09\"}", "{\"mes\":\"2026-10\"}");
    assertThat(RotaDados.apuracaoSemFuncao("Quanto vendi de soja?", HOJE)).isEmpty();
  }
}
