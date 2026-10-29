package br.com.agrocontabil.exception;

/**
 * Exceção 4xx que define o {@code message} de topo do envelope {@code ApiError} (issue #251).
 *
 * <p>Sem ela, um 4xx usa o {@code getMessage()} como topo. Implementa quem tem nele texto de log
 * ({@link XmlGenerationException}, {@link LimiteUsoAssistenteException}). Os 5xx não precisam: o
 * {@link GlobalExceptionHandler} usa a mensagem do {@code errors[0]}, segura por contrato.
 */
public interface ComMensagemCliente {

  /** Mensagem segura do {@code message} de topo do envelope. */
  String clientMessage();
}
