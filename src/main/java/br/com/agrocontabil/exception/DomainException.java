package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorDetail;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * Base de todas as exceções de domínio mapeadas para o contrato de erro estruturado.
 *
 * <p>Carrega o {@link HttpStatus} de resposta, a mensagem de envelope (amigável ao
 * cliente) e a lista de {@link ErrorDetail}. O {@code GlobalExceptionHandler} converte
 * qualquer subclasse num {@code ApiError} de forma uniforme.
 */
public abstract class DomainException extends RuntimeException {

  private final transient HttpStatus status;
  private final transient List<ErrorDetail> errors;

  protected DomainException(HttpStatus status, String message, List<ErrorDetail> errors) {
    super(message);
    this.status = status;
    // List.copyOf faz cópia defensiva real e imutável — desacopla da lista do chamador
    this.errors = errors == null ? List.of() : List.copyOf(errors);
  }

  /** Status HTTP de resposta para esta exceção. */
  public HttpStatus getStatus() {
    return status;
  }

  /** Lista imutável de erros estruturados associados. */
  public List<ErrorDetail> getErrors() {
    return errors;
  }
}
