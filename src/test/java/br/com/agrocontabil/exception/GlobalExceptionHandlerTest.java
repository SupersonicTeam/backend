package br.com.agrocontabil.exception;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.common.ApiError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * Testes unitários do {@link GlobalExceptionHandler} para casos que o MockMvc não consegue
 * disparar de ponta a ponta (issue Agrocontabil#66 — Sprint 5).
 *
 * <p>O limite de upload multipart é aplicado pelo container/multipart resolver real, ausente
 * no MockMvc — por isso o 413 é exercitado invocando o handler diretamente.
 */
class GlobalExceptionHandlerTest {

  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  @Test
  @DisplayName("Upload acima do limite responde 413 com envelope errors[]")
  void uploadAcimaDoLimiteResponde413ComEnvelope() {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/notas/importar");

    ResponseEntity<ApiError> response =
        handler.handleMaxUploadSize(new MaxUploadSizeExceededException(1_048_576L), request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
    ApiError body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.status()).isEqualTo(413);
    assertThat(body.path()).isEqualTo("/api/notas/importar");
    assertThat(body.errors()).hasSize(1);
    assertThat(body.errors().get(0).code()).isEqualTo("PAYLOAD_TOO_LARGE");
  }
}
