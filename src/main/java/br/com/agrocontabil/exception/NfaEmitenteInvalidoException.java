package br.com.agrocontabil.exception;

/**
 * Lancada quando o emitente de uma Nota Fiscal Avulsa (NFA) nao atende aos
 * requisitos do fluxo: o produtor precisa ser pessoa fisica (PF) com CPF valido.
 *
 * <p>Mapeada para HTTP 422 (Unprocessable Entity) pelo {@code GlobalExceptionHandler}.
 * A mensagem e generica ao cliente; o detalhe tecnico fica apenas no log.
 *
 * @see br.com.agrocontabil.service.GeracaoNfaService
 */
public class NfaEmitenteInvalidoException extends RuntimeException {

  public NfaEmitenteInvalidoException(String message) {
    super(message);
  }
}
