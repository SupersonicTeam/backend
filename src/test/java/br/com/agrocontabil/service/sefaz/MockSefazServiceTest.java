package br.com.agrocontabil.service.sefaz;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("MockSefazService — cancelamento mock")
class MockSefazServiceTest {

  private final MockSefazService service = new MockSefazService();

  @Test
  @DisplayName("retorna protocolo nao-vazio e dataHora ao cancelar")
  void cancelarRetornaProtocolo() {
    CancelamentoSefazResult result =
        service.cancelar("41260612345678000195550010000000011000000017", "Erro de digitacao nos dados");

    assertThat(result).isNotNull();
    assertThat(result.protocolo()).isNotBlank();
    assertThat(result.dataHora()).isNotNull();
  }
}
