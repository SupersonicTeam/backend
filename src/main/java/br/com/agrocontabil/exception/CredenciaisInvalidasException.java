package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * E-mail e senha não conferem (issue #206). HTTP 401 {@code CREDENCIAIS_INVALIDAS}.
 *
 * <p>Uma resposta só para e-mail inexistente, senha errada, credencial desativada e conta bloqueada
 * por excesso de tentativas (#208): distinguir os
 * casos transformaria o login numa consulta de quais e-mails têm conta (OWASP ASVS 2.2.x / WSTG
 * ATHN-04). O tempo de resposta também é igualado — ver {@code LoginService}.
 */
public class CredenciaisInvalidasException extends DomainException {

  private static final String MENSAGEM = "E-mail ou senha incorretos.";

  private static final String SUGESTAO =
      "Confira o e-mail e a senha. Depois de várias tentativas erradas o acesso fica bloqueado "
          + "por alguns minutos. Se esqueceu a senha, use a recuperação de senha.";

  public CredenciaisInvalidasException() {
    super(
        HttpStatus.UNAUTHORIZED,
        MENSAGEM,
        List.of(new ErrorDetail(
            ErrorCode.CREDENCIAIS_INVALIDAS.code(), null, MENSAGEM, SUGESTAO, null, null)));
  }
}
