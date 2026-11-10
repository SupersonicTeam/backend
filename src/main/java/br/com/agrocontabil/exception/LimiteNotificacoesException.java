package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * Teto de disparos manuais de notificação por produtor por hora (issue #182). HTTP 429 com
 * {@code Retry-After}.
 */
public class LimiteNotificacoesException extends DomainException implements ComRetryAfter {

  private final long retryAfterSegundos;

  /**
   * Cria a exceção.
   *
   * @param maximo             teto por hora
   * @param retryAfterSegundos segundos até a janela liberar um disparo
   */
  public LimiteNotificacoesException(int maximo, long retryAfterSegundos) {
    super(HttpStatus.TOO_MANY_REQUESTS, mensagem(maximo), List.of(new ErrorDetail(
        ErrorCode.LIMITE_NOTIFICACOES.code(), null, mensagem(maximo),
        "Aguarde e tente de novo mais tarde.", null, null)));
    this.retryAfterSegundos = Math.max(1, retryAfterSegundos);
  }

  private static String mensagem(int maximo) {
    return "Limite de " + maximo + " notificações por hora para este produtor atingido.";
  }

  @Override
  public long retryAfterSegundos() {
    return retryAfterSegundos;
  }
}
