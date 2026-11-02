package br.com.agrocontabil.assistente;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.assistente.SecaoResposta.Assunto;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Composição da resposta de dados (issue #266): ordem, resumo, seção vazia, teto e siglas. */
class ComposicaoRespostaDadosTest {

  private static SecaoResposta secao(Assunto assunto, String texto, int pontos, String resumo,
      String semPendencia, boolean pedida) {
    return new SecaoResposta(assunto, texto, pontos, resumo, semPendencia, pontos == 0, pedida,
        List.of());
  }

  @Test
  @DisplayName("ordem fixa de prioridade, qualquer que seja a ordem das chamadas")
  void ordemDePrioridade() {
    String texto = ComposicaoRespostaDados.compor(List.of(
        SecaoResposta.informativa("Apuracao.", false, List.of()),
        secao(Assunto.PEDIDOS_DO_ESCRITORIO, "Pedido.", 1, "1 pedido do escritorio", "pedidos",
            true),
        secao(Assunto.NOTAS_PARADAS, "Nota parada.", 1, "1 nota parada", "notas paradas", true),
        secao(Assunto.GUIAS_EM_ATRASO, "Atraso.", 2, "2 guias em atraso", "guias em atraso",
            true)));

    assertThat(texto).isEqualTo("Encontrei 4 pontos de atencao: 2 guias em atraso, 1 nota"
        + " parada e 1 pedido do escritorio.\n\nAtraso.\n\nNota parada.\n\nPedido.\n\nApuracao.");
  }

  @Test
  @DisplayName("resumo: vazias nao pedidas so como 'sem pendencias em', com o recorte")
  void vaziaNaoPedidaSoNoResumo() {
    String texto = ComposicaoRespostaDados.compor(List.of(
        secao(Assunto.GUIAS_EM_ATRASO, "Atraso.", 1, "1 guia em atraso", "guias em atraso",
            false),
        secao(Assunto.PEDIDOS_DO_ESCRITORIO, "Nenhum pedido.", 0, null, "pedidos do escritorio",
            false),
        secao(Assunto.NOTAS_A_CONFERIR, "Nada a conferir.", 0, null, "notas a conferir",
            false)));

    assertThat(texto).isEqualTo("Encontrei 1 ponto de atencao: 1 guia em atraso. Sem pendencias"
        + " em: pedidos do escritorio e notas a conferir.\n\nAtraso.");
  }

  @Test
  @DisplayName("sem nenhum ponto: o resumo diz onde procurou, com os recortes")
  void semPontos() {
    String texto = ComposicaoRespostaDados.compor(List.of(
        secao(Assunto.GUIAS_EM_ATRASO, "Sem atraso.", 0, null,
            "guias em atraso (vencimento ate 01/11/2026)", false),
        secao(Assunto.PRAZOS, "Sem prazo.", 0, null, "prazos dos proximos dias", false)));

    assertThat(texto).isEqualTo("Nao encontrei pontos de atencao em: guias em atraso"
        + " (vencimento ate 01/11/2026) e prazos dos proximos dias.");
  }

  @Test
  @DisplayName("secao vazia pedida aparece; com um assunto de atencao so, sem linha de resumo")
  void vaziaPedidaSemResumo() {
    String texto = ComposicaoRespostaDados.compor(List.of(
        secao(Assunto.GUIAS_EM_ATRASO, "Nao encontrei guias em atraso.", 0, null,
            "guias em atraso", true)));

    assertThat(texto).isEqualTo("Nao encontrei guias em atraso.");
  }

  @Test
  @DisplayName("a mesma secao duas vezes (chamadas iguais) sai uma vez; teto de secoes")
  void repetidaETeto() {
    SecaoResposta compras = SecaoResposta.informativa("Compras.", false, List.of());
    assertThat(ComposicaoRespostaDados.compor(List.of(compras, compras))).isEqualTo("Compras.");

    List<SecaoResposta> muitas = java.util.stream.IntStream.range(0, 9)
        .mapToObj(i -> SecaoResposta.informativa("Secao " + i + ".", false, List.of()))
        .toList();
    assertThat(ComposicaoRespostaDados.compor(muitas).split("\n\n"))
        .hasSize(ComposicaoRespostaDados.MAXIMO_SECOES);
  }

  @Test
  @DisplayName("sigla do glossario explicada na primeira vez, e so uma vez")
  void siglas() {
    assertThat(ComposicaoRespostaDados.explicarSiglas("IBS de R$ 1,00 e IBS de R$ 2,00; CBS."))
        .isEqualTo("IBS (imposto sobre bens e servicos) de R$ 1,00 e IBS de R$ 2,00; CBS"
            + " (contribuicao sobre bens e servicos).");
    // Ja explicada pelo texto: nao repete.
    assertThat(ComposicaoRespostaDados.explicarSiglas(
        "O imposto sobre bens e servicos (IBS) cresce.")).doesNotContain("IBS (");
  }

  @Test
  @DisplayName("sem secao: texto vazio (a rota responde que nao identificou o dado)")
  void semSecao() {
    assertThat(ComposicaoRespostaDados.compor(List.of())).isEmpty();
  }

  @Test
  @DisplayName("todas as de atencao pedidas e vazias: sem linha de resumo repetindo as frases")
  void vaziasPedidasSemResumo() {
    String texto = ComposicaoRespostaDados.compor(List.of(
        secao(Assunto.GUIAS_EM_ATRASO, "Sem atraso.", 0, null, "guias em atraso", true),
        secao(Assunto.PRAZOS, "Sem prazo.", 0, null, "prazos", true)));

    assertThat(texto).isEqualTo("Sem atraso.\n\nSem prazo.");
  }

  @Test
  @DisplayName("guias em atraso de duas funcoes contam uma vez no resumo")
  void atrasoContaUmaVez() {
    String texto = ComposicaoRespostaDados.compor(List.of(
        secao(Assunto.GUIAS_EM_ATRASO, "Atraso.", 2, "2 guias em atraso", "atraso", true),
        secao(Assunto.GUIAS_EM_ATRASO, "Mes com atraso.", 1, "1 guia em atraso", "atraso", true),
        secao(Assunto.NOTAS_PARADAS, "Parada.", 1, "1 nota parada", "paradas", true)));

    assertThat(texto).startsWith("Encontrei 3 pontos de atencao: 2 guias em atraso e 1 nota"
        + " parada.");
  }

  @Test
  @DisplayName("fontes do validador: a linha de resumo e o 'E mais N' calculados pelo backend")
  void fontesCalculadas() {
    List<SecaoResposta> secoes = List.of(
        secao(Assunto.GUIAS_EM_ATRASO, "Atraso:\n- x\nE mais 7, na tela de Calendario.", 9,
            "9 guias em atraso", "atraso", true),
        secao(Assunto.NOTAS_PARADAS, "Parada.", 2, "2 notas paradas", "paradas", true));

    assertThat(ComposicaoRespostaDados.textosLivres(secoes)).contains(
        "E mais 7, na tela de Calendario.",
        "Encontrei 11 pontos de atencao: 9 guias em atraso e 2 notas paradas.");
  }
}
