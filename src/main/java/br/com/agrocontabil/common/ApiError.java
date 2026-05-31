package br.com.agrocontabil.common;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Corpo padronizado de resposta de erro da API.
 *
 * <p>Usado por toda a aplicacao atraves do {@code GlobalExceptionHandler}.
 * Nunca expoe stack trace nem detalhes sensiveis ao cliente.
 *
 * @param timestamp instante do erro
 * @param status    codigo HTTP
 * @param error     descricao curta do status (ex: "Not Found")
 * @param message   mensagem amigavel para o cliente
 * @param path      caminho da requisicao que falhou
 * @param fields    erros de validacao por campo (opcional)
 */
public record ApiError(
        OffsetDateTime timestamp,
        int status,
        String error,
        String message,
        String path,
        List<FieldError> fields) {

    /**
     * Erro de validacao associado a um campo especifico.
     *
     * @param field   nome do campo
     * @param message motivo da rejeicao
     */
    public record FieldError(String field, String message) {
    }

    public static ApiError of(int status, String error, String message, String path) {
        return new ApiError(OffsetDateTime.now(), status, error, message, path, List.of());
    }

    public static ApiError of(int status, String error, String message, String path,
            List<FieldError> fields) {
        return new ApiError(OffsetDateTime.now(), status, error, message, path, fields);
    }
}
