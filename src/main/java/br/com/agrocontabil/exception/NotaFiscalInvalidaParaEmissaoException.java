package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * Lançada quando uma NotaFiscal não atende às pré-condições de emissão do XML. HTTP 422.
 * Cada violação vira um {@link ErrorDetail} com {@code code=NOTA_INVALIDA_EMISSAO}, ou com o
 * código e a sugestão próprios da violação (ex.: {@code ICMS_A_DEFINIR}).
 */
public class NotaFiscalInvalidaParaEmissaoException extends DomainException {

  /**
   * Registro imutável de uma violação de regra fiscal.
   *
   * @param field         campo
   * @param reason        mensagem ao cliente
   * @param code          código do erro; nulo = {@code NOTA_INVALIDA_EMISSAO}
   * @param suggestion    o que fazer; pode ser nula
   * @param rejectedValue valor recusado; pode ser nulo (#375)
   * @param expectedValue valor ou valores aceitos; pode ser nulo (#375)
   */
  public record FieldViolation(String field, String reason, ErrorCode code, String suggestion,
      String rejectedValue, String expectedValue) {

    /** Violação com o código padrão e sem sugestão. */
    public FieldViolation(String field, String reason) {
      this(field, reason, null, null, null, null);
    }

    /** Violação com código e sugestão próprios, sem valores recusado e esperado. */
    public FieldViolation(String field, String reason, ErrorCode code, String suggestion) {
      this(field, reason, code, suggestion, null, null);
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
        .map(v -> new ErrorDetail(
            (v.code() != null ? v.code() : ErrorCode.NOTA_INVALIDA_EMISSAO).code(), v.field(),
            v.reason(), v.suggestion(), v.rejectedValue(), v.expectedValue()))
        .toList();
  }
}
