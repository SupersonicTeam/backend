package br.com.agrocontabil.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** Validador de {@link CpfDoContador}: delega ao {@link DocumentoDoContador} (issue #519). */
public class CpfDoContadorValidator implements ConstraintValidator<CpfDoContador, String> {

  @Override
  public boolean isValid(String value, ConstraintValidatorContext context) {
    return !DocumentoDoContador.recusado(value);
  }
}
