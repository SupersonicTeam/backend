package br.com.agrocontabil.exception;

import java.util.Collections;
import java.util.List;

/**
 * Lançada quando uma {@link br.com.agrocontabil.model.NotaFiscal} não atende às
 * pré-condições necessárias para geração do XML da NF-e.
 *
 * <p>Diferentemente das exceções de Bean Validation (que validam o DTO de entrada),
 * esta exceção valida <strong>regras de emissão fiscal</strong> sobre a entidade
 * já persistida — campos que o layout da NF-e exige mas que podem estar ausentes
 * nos dados cadastrais do emissor ou destinatário.
 *
 * <p>Mapeada para HTTP 422 (Unprocessable Entity) pelo {@code GlobalExceptionHandler},
 * com a lista de campos e motivos exposta em {@code ApiError.fields}.
 *
 * @see br.com.agrocontabil.nfe.FiscalRulesValidator
 */
public class NotaFiscalInvalidaParaEmissaoException extends RuntimeException {

  /** Registro imutável de uma violação de regra fiscal. */
  public record FieldViolation(String field, String reason) {}

  private final List<FieldViolation> violations;

  /**
   * Cria a exceção com a lista de violações agregadas.
   *
   * @param violations lista de campos inválidos e motivos
   */
  public NotaFiscalInvalidaParaEmissaoException(List<FieldViolation> violations) {
    super(buildMessage(violations));
    this.violations = Collections.unmodifiableList(violations);
  }

  /**
   * Retorna a lista imutável de violações identificadas pelo validador.
   *
   * @return violações de regra de emissão
   */
  public List<FieldViolation> getViolations() {
    return violations;
  }

  private static String buildMessage(List<FieldViolation> violations) {
    return "Nota fiscal invalida para emissao. "
        + violations.size()
        + " violacao(oes): "
        + violations.stream()
            .map(v -> "[" + v.field() + ": " + v.reason() + "]")
            .reduce("", (a, b) -> a + " " + b)
            .trim();
  }
}
