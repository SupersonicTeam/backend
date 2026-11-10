package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * A senha atual informada na troca de senha não confere (issue #209). HTTP 400
 * {@code SENHA_ATUAL_INCORRETA}.
 *
 * <p>400, e não 401: a requisição está autenticada; o que falhou foi a prova adicional. Um 401
 * faria o frontend tentar renovar a sessão. A troca passa pelo mesmo bloqueio por tentativas do
 * login: com um access token roubado, o atacante não adivinha a senha atual por aqui.
 */
public class SenhaAtualIncorretaException extends DomainException {

  private static final String MENSAGEM = "A senha atual não confere.";

  public SenhaAtualIncorretaException() {
    super(
        HttpStatus.BAD_REQUEST,
        MENSAGEM,
        List.of(new ErrorDetail(ErrorCode.SENHA_ATUAL_INCORRETA.code(), "senhaAtual", MENSAGEM,
            "Confira a senha atual. Depois de várias tentativas erradas a conta fica bloqueada "
                + "por alguns minutos.", null, null)));
  }
}
