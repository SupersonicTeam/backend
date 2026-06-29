package br.com.agrocontabil.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ErrorCodeTest {

  @Test
  void codeShouldBeStableStringIdentifier() {
    assertThat(ErrorCode.VALIDATION_ERROR.code()).isEqualTo("VALIDATION_ERROR");
    assertThat(ErrorCode.RESOURCE_NOT_FOUND.code()).isEqualTo("RESOURCE_NOT_FOUND");
    assertThat(ErrorCode.NOTA_NAO_EDITAVEL.code()).isEqualTo("NOTA_NAO_EDITAVEL");
  }
}
