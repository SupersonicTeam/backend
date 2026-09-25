package br.com.agrocontabil.sefaz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.config.RetryConfig;
import br.com.agrocontabil.exception.SefazComunicacaoException;
import br.com.agrocontabil.exception.SefazConexaoException;
import br.com.agrocontabil.exception.SefazRespostaIndeterminadaException;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/**
 * Política de retry do {@link SefazClient} pela idempotência da operação (issue #83).
 *
 * <p>Sobe só o {@link RetryConfig} e o client: o que se testa é o {@code @Retryable} aplicado
 * pelo proxy do Spring Retry, que um teste Mockito puro não enxerga.
 *
 * <p>O defeito travado aqui: {@code enviar} e {@code cancelar} re-tentavam qualquer
 * {@link SefazComunicacaoException}. Um timeout de leitura depois de a SEFAZ autorizar virava um
 * reenvio do mesmo XML, e a SEFAZ respondia duplicidade (cStat 204).
 */
@SpringJUnitConfig(classes = {RetryConfig.class, SefazClient.class})
@TestPropertySource(properties = {
    "app.nfe.sefaz.retry.max-attempts=3",
    "app.nfe.sefaz.retry.backoff-ms=1"})
class SefazClientRetryTest {

  private static final String CHAVE = "41262612345678000195550010000000011000000012";

  @Autowired private SefazClient sefazClient;

  @MockitoBean private SefazTransport transport;

  @BeforeEach
  void setUp() {
    reset(transport);
  }

  @Test
  @DisplayName("enviar re-tenta falha de conexao e sucede na tentativa seguinte")
  void enviarRetentaConexao() {
    when(transport.enviar(any(), any(), any()))
        .thenThrow(new SefazConexaoException("connection refused"))
        .thenReturn(autorizada());

    assertThat(sefazClient.enviar("<xml/>", CHAVE, SefazAmbiente.HOMOLOGACAO).cStat())
        .isEqualTo("100");
    verify(transport, times(2)).enviar(any(), any(), any());
  }

  @Test
  @DisplayName("enviar esgota as tentativas de conexao e propaga a SefazConexaoException")
  void enviarEsgotaConexao() {
    when(transport.enviar(any(), any(), any()))
        .thenThrow(new SefazConexaoException("connection refused"));

    assertThatThrownBy(() -> sefazClient.enviar("<xml/>", CHAVE, SefazAmbiente.HOMOLOGACAO))
        .isInstanceOf(SefazConexaoException.class);
    verify(transport, times(3)).enviar(any(), any(), any());
  }

  @Test
  @DisplayName("enviar NAO re-tenta resposta indeterminada (evita duplicidade 204)")
  void enviarNaoRetentaIndeterminada() {
    when(transport.enviar(any(), any(), any()))
        .thenThrow(new SefazRespostaIndeterminadaException("read timeout"));

    assertThatThrownBy(() -> sefazClient.enviar("<xml/>", CHAVE, SefazAmbiente.HOMOLOGACAO))
        .isInstanceOf(SefazRespostaIndeterminadaException.class);
    verify(transport, times(1)).enviar(any(), any(), any());
  }

  @Test
  @DisplayName("enviar NAO re-tenta falha de comunicacao nao classificada (tratada como incerta)")
  void enviarNaoRetentaComunicacaoGenerica() {
    when(transport.enviar(any(), any(), any()))
        .thenThrow(new SefazComunicacaoException("falha sem classificacao"));

    assertThatThrownBy(() -> sefazClient.enviar("<xml/>", CHAVE, SefazAmbiente.HOMOLOGACAO))
        .isInstanceOf(SefazComunicacaoException.class);
    verify(transport, times(1)).enviar(any(), any(), any());
  }

  @Test
  @DisplayName("cancelar NAO re-tenta resposta indeterminada (evita duplicidade de evento 573)")
  void cancelarNaoRetentaIndeterminada() {
    when(transport.cancelar(any(), any(), any(), any()))
        .thenThrow(new SefazRespostaIndeterminadaException("read timeout"));

    assertThatThrownBy(() -> sefazClient.cancelar(
        CHAVE, "motivo com mais de 15", "1", SefazAmbiente.HOMOLOGACAO))
        .isInstanceOf(SefazRespostaIndeterminadaException.class);
    verify(transport, times(1)).cancelar(any(), any(), any(), any());
  }

  @Test
  @DisplayName("cancelar re-tenta falha de conexao")
  void cancelarRetentaConexao() {
    when(transport.cancelar(any(), any(), any(), any()))
        .thenThrow(new SefazConexaoException("connection refused"));

    assertThatThrownBy(() -> sefazClient.cancelar(
        CHAVE, "motivo com mais de 15", "1", SefazAmbiente.HOMOLOGACAO))
        .isInstanceOf(SefazConexaoException.class);
    verify(transport, times(3)).cancelar(any(), any(), any(), any());
  }

  @Test
  @DisplayName("consultarStatus e leitura: re-tenta tambem a resposta indeterminada")
  void consultarRetentaIndeterminada() {
    when(transport.consultarStatus(any(), any()))
        .thenThrow(new SefazRespostaIndeterminadaException("read timeout"))
        .thenReturn(autorizada());

    assertThat(sefazClient.consultarStatus(CHAVE, SefazAmbiente.HOMOLOGACAO).cStat())
        .isEqualTo("100");
    verify(transport, times(2)).consultarStatus(any(), any());
  }

  private static SefazRetorno autorizada() {
    return new SefazRetorno(SituacaoSefaz.AUTORIZADA, "100", "Autorizado o uso da NF-e",
        "141260000000017", OffsetDateTime.now());
  }
}
