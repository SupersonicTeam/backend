package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * Lancada quando o emitente de uma Nota Fiscal Avulsa (NFA) nao atende aos
 * requisitos do fluxo: o producer precisa ser pessoa fisica (PF) com CPF valido.
 *
 * <p>Mapeada para HTTP 422 (Unprocessable Entity) via {@link DomainException}.
 *
 * @see br.com.agrocontabil.service.NfaXmlService
 */
public class NfaInvalidIssuerException extends DomainException {

  public NfaInvalidIssuerException(String message) {
    super(
        HttpStatus.UNPROCESSABLE_ENTITY,
        message,
        List.of(ErrorDetail.of(ErrorCode.NFA_EMITENTE_INVALIDO, message)));
  }
}
