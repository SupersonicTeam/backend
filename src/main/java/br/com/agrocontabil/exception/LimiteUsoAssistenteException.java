package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * O titular não tem créditos da semana para o teto de uma pergunta à Ceres (issue #166). HTTP 429
 * {@code LIMITE_USO_ASSISTENTE} com {@code Retry-After} até a virada da semana.
 *
 * <p>429, e não 503 como o {@link AssistenteOcupadoException}: aqui o limite é a cota do cliente,
 * não a capacidade do servidor. A mensagem fala em créditos, nunca em tokens.
 */
public class LimiteUsoAssistenteException extends DomainException
    implements ComRetryAfter, ComMensagemCliente {

  /** Mensagem segura para o cliente da API (também o {@code message} de topo, #251). */
  public static final String MENSAGEM_CLIENTE =
      "Os créditos da Ceres desta semana acabaram. Eles renovam na segunda-feira.";

  private final long retryAfterSegundos;

  /**
   * Constrói a recusa.
   *
   * @param retryAfterSegundos segundos até a semana virar (no mínimo 1)
   */
  public LimiteUsoAssistenteException(long retryAfterSegundos) {
    super(HttpStatus.TOO_MANY_REQUESTS, "Creditos semanais da Ceres esgotados para o titular",
        List.of(ErrorDetail.of(ErrorCode.LIMITE_USO_ASSISTENTE, MENSAGEM_CLIENTE)));
    this.retryAfterSegundos = Math.max(1, retryAfterSegundos);
  }

  @Override
  public long retryAfterSegundos() {
    return retryAfterSegundos;
  }

  @Override
  public String clientMessage() {
    return MENSAGEM_CLIENTE;
  }
}
