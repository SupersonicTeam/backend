package br.com.agrocontabil.validation;

import br.com.agrocontabil.common.DocumentoFiscalValidator;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Validador do digito verificador de CPF/CNPJ (issue #18).
 *
 * <p>Ao contrario dos validadores de NCM/CFOP (que consultam tabelas de referencia), este
 * validador e puramente algoritmico e sem estado — nao depende de repositorio nem de cache,
 * logo funciona identicamente em contextos com ou sem JPA (ex.: {@code @WebMvcTest}). Delega o
 * calculo do digito verificador ao {@link DocumentoFiscalValidator}, a mesma logica reusada
 * programaticamente para validar o documento do emitente no fluxo NFA.
 */
public class CpfCnpjValidator implements ConstraintValidator<ValidCpfCnpj, String> {

  @Override
  public boolean isValid(String value, ConstraintValidatorContext context) {
    // Convencao Bean Validation: null/blank sao responsabilidade de @NotBlank/@Pattern.
    if (value == null || value.isBlank()) {
      return true;
    }
    return DocumentoFiscalValidator.isCpfCnpjValido(value);
  }
}
