package br.com.agrocontabil.common.validation;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintValidatorContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

@DisplayName("CpfValidator — constraint @Cpf")
class CpfValidatorTest {

  private CpfValidator validator;
  private ConstraintValidatorContext context;

  @BeforeEach
  void setUp() {
    validator = new CpfValidator();
    context = Mockito.mock(ConstraintValidatorContext.class);
  }

  @Test
  @DisplayName("considera valido um CPF correto")
  void cpfValido() {
    assertThat(validator.isValid("52998224725", context)).isTrue();
  }

  @Test
  @DisplayName("considera invalido um CPF com DV errado")
  void cpfInvalido() {
    assertThat(validator.isValid("12345678901", context)).isFalse();
  }

  @Test
  @DisplayName("considera valido null (obrigatoriedade fica a cargo de @NotBlank)")
  void nullEhValido() {
    assertThat(validator.isValid(null, context)).isTrue();
  }
}
