package br.com.agrocontabil.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ApiErrorTest {

  @Test
  void ofWithoutErrorsShouldProduceEmptyErrorsList() {
    ApiError error = ApiError.of(404, "Not Found", "Nao encontrado", "/api/notas/1");
    assertThat(error.errors()).isEmpty();
    assertThat(error.status()).isEqualTo(404);
  }

  @Test
  void ofWithErrorsShouldExposeThem() {
    ErrorDetail detail = ErrorDetail.of(ErrorCode.VALIDATION_ERROR, "ncm", "invalido");
    ApiError error = ApiError.of(400, "Bad Request", "Campos invalidos", "/api/notas",
        List.of(detail));
    assertThat(error.errors()).containsExactly(detail);
  }
}
