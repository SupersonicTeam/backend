package br.com.agrocontabil.exception;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.common.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class AssinaturaDigitalExceptionTest {

  @Test
  void shouldMapToInternalServerErrorWithCode() {
    AssinaturaDigitalException ex =
        new AssinaturaDigitalException(ErrorCode.CERTIFICADO_EXPIRADO, "cert vencido em 2020");
    assertThat(ex.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(ex.getErrors()).hasSize(1);
    assertThat(ex.getErrors().get(0).code()).isEqualTo("CERTIFICADO_EXPIRADO");
    // detalhe tecnico fica em getMessage (log), nao no ErrorDetail exposto
    assertThat(ex.getMessage()).contains("cert vencido");
    assertThat(ex.getErrors().get(0).message()).doesNotContain("cert vencido");
  }
}
