package br.com.agrocontabil.common.validation;

import br.com.agrocontabil.repository.RuralProducerCfopRepository;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Implementa {@link ValidCfop} consultando a tabela de referência via
 * {@link RuralProducerCfopRepository}. Injeção de construtor pela fábrica de
 * validators do Spring. {@code null}/blank → válido.
 */
public class CfopValidator implements ConstraintValidator<ValidCfop, String> {

  private final RuralProducerCfopRepository repository;

  public CfopValidator(RuralProducerCfopRepository repository) {
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
