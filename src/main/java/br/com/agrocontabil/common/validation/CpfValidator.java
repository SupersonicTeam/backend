package br.com.agrocontabil.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Implementa a constraint {@link Cpf} delegando a {@link CpfUtils#isValid(String)}.
 *
 * <p>Valores {@code null} sao tratados como validos (a obrigatoriedade e
 * responsabilidade de {@code @NotBlank}/{@code @NotNull}).
 */
public class CpfValidator implements ConstraintValidator<Cpf, String> {

  @Override
  public boolean isValid(String value, ConstraintValidatorContext context) {
    if (value == null) {
      return true;
    }
    return CpfUtils.isValid(value);
  }
}
