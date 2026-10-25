package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import java.util.List;
import org.springframework.http.HttpStatus;

/** Obrigação do catálogo inválida (issue #181) — HTTP 400 {@code OBRIGACAO_INVALIDA}. */
public class ObrigacaoInvalidaException extends DomainException {

  /**
   * Cria a exceção.
   *
   * @param campo    campo recusado
   * @param mensagem o que está errado
   * @param sugestao como corrigir
   */
  public ObrigacaoInvalidaException(String campo, String mensagem, String sugestao) {
    super(HttpStatus.BAD_REQUEST, mensagem, List.of(new ErrorDetail(
        ErrorCode.OBRIGACAO_INVALIDA.code(), campo, mensagem, sugestao, null, null)));
  }
}
