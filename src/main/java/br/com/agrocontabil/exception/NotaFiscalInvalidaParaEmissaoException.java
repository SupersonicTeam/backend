package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * Lançada quando uma NotaFiscal não atende às pré-condições de emissão do XML. HTTP 422.
 * Cada violação vira um {@link ErrorDetail} com {@code code=NOTA_INVALIDA_EMISSAO}.
 */
public class NotaFiscalInvalidaParaEmissaoException extends DomainException {

  /**
   * Registro imutável de uma violação de regra fiscal.
   *
   * @param field campo
   * @param reason mensagem ao cliente
   * @param suggestion como corrigir; pode ser nula
   * @param rejectedValue valor recusado; pode ser nulo
   * @param expectedValue valor ou valores aceitos; pode ser nulo
   */
  public record FieldViolation(String field, String reason, String suggestion,
      String rejectedValue, String expectedValue) {

    /** Violação só com campo e mensagem. */
    public FieldViolation(String field, String reason) {
      this(field, reason, null, null, null);
    }
  }

  public NotaFiscalInvalidaParaEmissaoException(List<FieldViolation> violations) {
    super(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "Nota fiscal invalida para emissao da NF-e. Corrija os campos listados.",
        toErrors(violations));
  }

  private static List<ErrorDetail> toErrors(List<FieldViolation> violations) {
    return violations.stream()
        .map(v -> new ErrorDetail(ErrorCode.NOTA_INVALIDA_EMISSAO.code(), v.field(), v.reason(),
            v.suggestion(), v.rejectedValue(), v.expectedValue()))
        .toList();
  }
}
