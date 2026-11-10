package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorDetail;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * A senha escolhida não atende a política de senha (issue #205). HTTP 400 {@code SENHA_FRACA}.
 *
 * <p>Os {@link ErrorDetail} nunca levam {@code rejectedValue}: devolver a senha no corpo do erro
 * a deixaria em log de proxy, em cache e no histórico do navegador.
 */
public class SenhaFracaException extends DomainException {

  /**
   * Cria a exceção com um item por regra violada.
   *
   * @param violacoes regras que a senha não cumpre (sem o valor da senha)
   */
  public SenhaFracaException(List<ErrorDetail> violacoes) {
    super(HttpStatus.BAD_REQUEST, "A senha não atende à política de senha.", violacoes);
  }
}
