package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * O produtor não tem telefone (WhatsApp) cadastrado (issue #182): HTTP 422 e nada é registrado.
 * Distinto de "nada a avisar", que é 200 com {@code enviados = 0}.
 */
public class ContatoAusenteException extends DomainException {

  private static final String MENSAGEM = "O produtor não tem WhatsApp cadastrado.";

  /** Cria a exceção. */
  public ContatoAusenteException() {
    super(HttpStatus.UNPROCESSABLE_ENTITY, MENSAGEM, List.of(new ErrorDetail(
        ErrorCode.CONTATO_AUSENTE.code(), "telefone", MENSAGEM,
        "Cadastre o telefone com WhatsApp no perfil do produtor e tente de novo.", null, null)));
  }
}
