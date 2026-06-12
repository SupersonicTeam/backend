package br.com.agrocontabil.exception;

/**
 * Violação de uma regra de negócio que impede a conclusão da operação, embora a
 * requisição esteja sintaticamente válida.
 *
 * <p>Mapeada para HTTP 422 (Unprocessable Entity) pelo {@code GlobalExceptionHandler}.
 * A mensagem é descritiva e segura para o cliente. Usada, por exemplo, pela regra
 * dos 30 dias do cancelamento de NF-e (RN006).
 */
public class BusinessException extends RuntimeException {

  public BusinessException(String message) {
    super(message);
  }
}
