package br.com.agrocontabil.common.validation;

import br.com.agrocontabil.repository.CfopProdutorRuralRepository;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Implementa {@link ValidCfop} consultando a tabela de referência via
 * {@link CfopProdutorRuralRepository}. Injeção de construtor pela fábrica de
 * validators do Spring. {@code null}/blank → válido.
 */
public class CfopValidator implements ConstraintValidator<ValidCfop, String> {

  private final CfopProdutorRuralRepository repository;

  public CfopValidator(CfopProdutorRuralRepository repository) {
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
