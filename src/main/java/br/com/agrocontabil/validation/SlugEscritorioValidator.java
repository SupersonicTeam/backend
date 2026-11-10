package br.com.agrocontabil.validation;

import br.com.agrocontabil.common.FormatoSlug;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** Valida {@link SlugEscritorio} pela regra única do {@link FormatoSlug} (issue #464). */
public class SlugEscritorioValidator implements ConstraintValidator<SlugEscritorio, String> {

  @Override
  public boolean isValid(String valor, ConstraintValidatorContext context) {
    return valor == null || FormatoSlug.valido(FormatoSlug.normalizar(valor));
  }
}
