package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * A operação deixaria o escritório sem administrador (issue #228) — HTTP 409.
 */
public class UltimoAdministradorException extends DomainException {

  private static final String MENSAGEM = "O escritório ficaria sem administrador.";

  /** Cria a exceção. */
  public UltimoAdministradorException() {
    super(HttpStatus.CONFLICT, MENSAGEM, List.of(new ErrorDetail(
        ErrorCode.ULTIMO_ADMINISTRADOR.code(),
        "funcao",
        MENSAGEM,
        "Promova outro membro a ADMINISTRADOR antes (PATCH /api/escritorio/membros/{id}).",
        null,
        "ADMINISTRADOR")));
  }
}
