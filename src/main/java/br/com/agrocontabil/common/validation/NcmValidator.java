package br.com.agrocontabil.common.validation;

import br.com.agrocontabil.repository.NcmAgropecuarioRepository;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Implementa {@link ValidNcm} consultando a tabela de referência via
 * {@link NcmAgropecuarioRepository}. Instanciado pela fábrica de validators do
 * Spring com injeção de construtor (o repositório vem do contexto).
 *
 * <p>{@code null}/blank → válido (defere ao {@code @NotBlank}).
 */
public class NcmValidator implements ConstraintValidator<ValidNcm, String> {

  private final NcmAgropecuarioRepository repository;

  public NcmValidator(NcmAgropecuarioRepository repository) {
    this.repository = repository;
  }

  @Override
  public boolean isValid(String value, ConstraintValidatorContext context) {
    if (value == null || value.isBlank()) {
      return true;
    }
    return repository.existsByCodigo(value);
  }
}
