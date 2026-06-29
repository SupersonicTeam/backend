package br.com.agrocontabil.common;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Corpo padronizado de resposta de error da API.
 *
 * <p>Usado por toda a aplicacao através do {@code GlobalExceptionHandler}.
 * Nunca expõe stack trace nem detalhes sensíveis ao cliente.
 *
 * @param timestamp instante do error
 * @param status    código HTTP
 * @param error     descrição curta do status (ex.: "Not Found")
 * @param message   mensagem amigável (envelope) para o cliente
 * @param path      caminho da requisição que falhou
 * @param errors    lista de erros estruturados (pode ser vazia)
 */
public record ApiError(
    OffsetDateTime timestamp,
    int status,
    String error,
    String message,
    String path,
    List<ErrorDetail> errors) {

  public static ApiError of(int status, String error, String message, String path) {
    return new ApiError(OffsetDateTime.now(), status, error, message, path, List.of());
  }

  public static ApiError of(int status, String error, String message, String path,
      List<ErrorDetail> errors) {
    return new ApiError(OffsetDateTime.now(), status, error, message, path, errors);
  }
}
