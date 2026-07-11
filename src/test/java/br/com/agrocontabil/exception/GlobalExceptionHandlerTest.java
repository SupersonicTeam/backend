package br.com.agrocontabil.exception;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.common.ApiError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
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

  @Test
  @DisplayName("Violacao de integridade (unique/FK) responde 409 sem vazar detalhe tecnico")
  void violacaoDeIntegridadeResponde409SemVazarDetalhe() {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/notas");

    ResponseEntity<ApiError> response = handler.handleDataIntegrityViolation(
        new DataIntegrityViolationException(
            "could not execute statement; constraint [uk_nota_fiscal_produtor_numero_serie_modelo]"),
        request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    ApiError body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.status()).isEqualTo(409);
    assertThat(body.path()).isEqualTo("/api/notas");
    assertThat(body.errors()).hasSize(1);
    assertThat(body.errors().get(0).code()).isEqualTo("VIOLACAO_INTEGRIDADE");
    // Nunca vaza o nome da constraint / SQL ao cliente — detalhe tecnico fica so no log.
    assertThat(body.errors().get(0).message()).doesNotContain("uk_");
    assertThat(body.message()).doesNotContain("constraint");
  }
}
