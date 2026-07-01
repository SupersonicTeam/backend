package br.com.agrocontabil.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Testes unitarios do {@link CpfCnpjValidator} (issue #18).
 *
 * <p>Delega ao {@link br.com.agrocontabil.common.DocumentoFiscalValidator} a validacao do
 * digito verificador. Convencao Bean Validation: {@code null}/blank retornam {@code true}
 * (o {@code @NotBlank}/{@code @Pattern} ja cuidam da obrigatoriedade/formato).
 */
class CpfCnpjValidatorTest {

  private CpfCnpjValidator validator;

  @BeforeEach
  void setUp() {
    validator = new CpfCnpjValidator();
  }

  @Test
  @DisplayName("CPF com DV valido deve ser aceito")
  void cpfValidoDeveSerAceito() {
    assertThat(validator.isValid("52998224725", null)).isTrue();
  }

  @Test
  @DisplayName("CNPJ com DV valido deve ser aceito")
  void cnpjValidoDeveSerAceito() {
    assertThat(validator.isValid("11222333000181", null)).isTrue();
  }

  @Test
  @DisplayName("CPF com DV invalido deve ser rejeitado")
  void cpfInvalidoDeveSerRejeitado() {
    assertThat(validator.isValid("12345678901", null)).isFalse();
  }

  @Test
  @DisplayName("CNPJ com DV invalido deve ser rejeitado")
  void cnpjInvalidoDeveSerRejeitado() {
    assertThat(validator.isValid("11222333000182", null)).isFalse();
  }

  @Test
  @DisplayName("null e blank sao validos (delegado a @NotBlank/@Pattern)")
  void nullOuBlankDeveSerValido() {
    assertThat(validator.isValid(null, null)).isTrue();
    assertThat(validator.isValid("   ", null)).isTrue();
  }

  @Test
  @DisplayName("Comprimento fora de 11/14 e rejeitado (formato ja tratado, mas defensivo)")
  void comprimentoInesperadoDeveSerRejeitado() {
    assertThat(validator.isValid("123", null)).isFalse();
  }
}
