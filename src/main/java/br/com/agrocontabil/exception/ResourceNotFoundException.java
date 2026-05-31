package br.com.agrocontabil.exception;

/**
 * Lancada quando um recurso de dominio nao e encontrado.
 *
 * <p>Mapeada para HTTP 404 pelo {@code GlobalExceptionHandler}.
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }

    public ResourceNotFoundException(String resource, Object id) {
        super("%s nao encontrado(a) para o identificador '%s'".formatted(resource, id));
    }
}
