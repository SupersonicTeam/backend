package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * O escritório passou do teto de convites por dia (issue #177) — HTTP 429 com
 * {@code Retry-After}.
 *
 * <p>Cada convite manda um e-mail para um endereço que o escritório digita; sem teto, a rota
 * viraria disparador de e-mail em massa em nome do AgroContabil.
 */
public class LimiteConvitesException extends DomainException implements ComRetryAfter {

  private final long retryAfterSegundos;

  /**
   * Cria a exceção.
   *
   * @param maximo             teto por dia
   * @param retryAfterSegundos segundos até a janela liberar um convite
   */
  public LimiteConvitesException(int maximo, long retryAfterSegundos) {
    super(HttpStatus.TOO_MANY_REQUESTS, mensagem(maximo), List.of(new ErrorDetail(
        ErrorCode.LIMITE_CONVITES.code(),
        null,
        mensagem(maximo),
        "Aguarde e tente de novo mais tarde.",
        null,
        null)));
    this.retryAfterSegundos = Math.max(1, retryAfterSegundos);
  }

  private static String mensagem(int maximo) {
    return "Limite de " + maximo + " convites em 24 horas atingido.";
  }

  @Override
  public long retryAfterSegundos() {
    return retryAfterSegundos;
  }
}
