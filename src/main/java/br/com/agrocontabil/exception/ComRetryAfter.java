package br.com.agrocontabil.exception;

/**
 * Exceção cuja resposta leva o header {@code Retry-After} (RFC 9110 §10.2.3): diz ao cliente em
 * quantos segundos vale tentar de novo. O {@link GlobalExceptionHandler} põe o header para toda
 * exceção que implementa esta interface (503 da Ceres ocupada, 429 da cota semanal).
 */
public interface ComRetryAfter {

  /** Segundos até valer a pena tentar de novo (sempre &gt;= 1). */
  long retryAfterSegundos();
}
