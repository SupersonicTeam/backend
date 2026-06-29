package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * Lancada quando o emitente de uma Nota Fiscal Avulsa (NFA) nao atende aos
 * requisitos do fluxo: o produtor precisa ser pessoa fisica (PF) com CPF valido.
 *
 * <p>Mapeada para HTTP 422 (Unprocessable Entity) via {@link DomainException}.
 *
 * @see br.com.agrocontabil.service.GeracaoNfaService
 */
public class NfaEmitenteInvalidoException extends DomainException {

  public NfaEmitenteInvalidoException(String message) {
    super(
        HttpStatus.UNPROCESSABLE_ENTITY,
        message,
        List.of(ErrorDetail.of(ErrorCode.NFA_EMITENTE_INVALIDO, message)));
  }
}
