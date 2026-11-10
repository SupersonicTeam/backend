package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * Lançada quando um recurso de domínio não é encontrado. HTTP 404.
 */
public class ResourceNotFoundException extends DomainException {

  public ResourceNotFoundException(String message) {
    super(HttpStatus.NOT_FOUND, message,
        List.of(ErrorDetail.of(ErrorCode.RESOURCE_NOT_FOUND, message)));
  }

  public ResourceNotFoundException(String resource, Object id) {
    this("%s não encontrado(a) para o identificador '%s'".formatted(resource, id));
  }
}
