package br.com.agrocontabil.assistente.continuidade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.assistente.ContextoAssistente;
import br.com.agrocontabil.assistente.continuidade.ConferenciaConsulta.DuvidaDeProduto;
import br.com.agrocontabil.config.AssistenteProperties;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * O julgamento de produto vem ligado por padrão (decisão do Murillo, 2026-11-19), mas só tem efeito
 * com a continuidade ligada, que segue desligada (#591).
 */
class ProvaDeProdutoTest {

  private static final DuvidaDeProduto DUVIDA =
      new DuvidaDeProduto("diesel", List.of("herbicida"));

  private final ContextoAssistente contexto =
      new ContextoAssistente(UUID.randomUUID(), LocalDate.of(2026, 11, 19));
  private final JulgamentoDeProduto julgamento = mock(JulgamentoDeProduto.class);

  @Test
  @DisplayName("Padrões: a chave do julgamento ligada, a continuidade desligada")
  void padroes() {
    AssistenteProperties propriedades = new AssistenteProperties();

    assertThat(propriedades.getContinuidade().isJulgamentoDeProduto()).isTrue();
    assertThat(propriedades.getContinuidade().isHabilitada()).isFalse();
  }

  @Test
  @DisplayName("Continuidade desligada: nenhum julgamento, mesmo com a chave ligada")
  void continuidadeDesligadaNaoJulga() {
    // Os padrões: chave do julgamento ligada, continuidade desligada.
    ProvaDeProduto prova = new ProvaDeProduto(ResolucaoProduto.NENHUMA, julgamento,
        new AssistenteProperties());

    assertThat(prova.comJulgamento()).isFalse();
    assertThat(prova.julgaOutro(DUVIDA, contexto)).isFalse();
    verifyNoInteractions(julgamento);
  }

  @Test
  @DisplayName("Continuidade desligada: a dúvida de produto reexecuta, o caminho de antes")
  void continuidadeDesligadaNaoMudaOCaminho() {
    ProvaDeProduto prova = new ProvaDeProduto(ResolucaoProduto.NENHUMA, julgamento,
        new AssistenteProperties());
    ParConferido par = new ParConferido("quanto comprei de herbicida em setembro?", "Resposta.",
        "v1.a.b", new CargaTurno(
            java.time.LocalDate.of(2026, 11, 10).atTime(15, 30)
                .atZone(java.time.ZoneId.of("America/Sao_Paulo")).toEpochSecond(),
            br.com.agrocontabil.assistente.orquestracao.Intencao.DADOS, false,
            List.of(ChamadaAssinada.de("totalComprasPorInsumo", "{\"descricaoOuNcm\":\"diesel\","
                + "\"dataInicio\":\"2026-09-01\",\"dataFim\":\"2026-09-30\"}").orElseThrow()),
            HashDados.de("x"), null, false, null), 0, false);

    assertThat(DecisaoContinuidade.avaliarConsulta(par, prova))
        .isEqualTo(DecisaoContinuidade.Consulta.REEXECUTAR);
    verifyNoInteractions(julgamento);
  }

  @Test
  @DisplayName("Continuidade ligada e a chave no padrão: o julgamento roda")
  void continuidadeLigadaJulga() {
    AssistenteProperties propriedades = new AssistenteProperties();
    propriedades.getContinuidade().setHabilitada(true);
    when(julgamento.ehOutroProduto(any(), any())).thenReturn(true);
    ProvaDeProduto prova = new ProvaDeProduto(ResolucaoProduto.NENHUMA, julgamento, propriedades);

    assertThat(prova.comJulgamento()).isTrue();
    assertThat(prova.julgaOutro(DUVIDA, contexto)).isTrue();
    verify(julgamento).ehOutroProduto(DUVIDA, contexto);
  }

  @Test
  @DisplayName("Continuidade ligada e a chave desligada: sem julgamento (como desligar)")
  void chaveDesligada() {
    AssistenteProperties propriedades = new AssistenteProperties();
    propriedades.getContinuidade().setHabilitada(true);
    propriedades.getContinuidade().setJulgamentoDeProduto(false);
    ProvaDeProduto prova = new ProvaDeProduto(ResolucaoProduto.NENHUMA, julgamento, propriedades);

    assertThat(prova.comJulgamento()).isFalse();
    assertThat(prova.julgaOutro(DUVIDA, contexto)).isFalse();
    verifyNoInteractions(julgamento);
  }
}
