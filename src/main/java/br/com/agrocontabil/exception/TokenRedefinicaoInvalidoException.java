package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * O link de redefinição de senha não vale (issue #209). HTTP 400
 * {@code TOKEN_REDEFINICAO_INVALIDO}.
 *
 * <p>Uma resposta só para desconhecido, expirado, já usado, substituído por um pedido mais novo
 * ou de conta desativada: o detalhe ajudaria quem testa links, não quem perdeu a senha.
 */
public class TokenRedefinicaoInvalidoException extends DomainException {

  private static final String MENSAGEM = "Link de redefinição de senha inválido ou expirado.";

  private static final String SUGESTAO =
      "Peça um link novo em 'Esqueci minha senha'. Cada link vale uma vez e por pouco tempo.";

  public TokenRedefinicaoInvalidoException() {
    super(
        HttpStatus.BAD_REQUEST,
        MENSAGEM,
        List.of(new ErrorDetail(ErrorCode.TOKEN_REDEFINICAO_INVALIDO.code(), "token", MENSAGEM,
            SUGESTAO, null, null)));
  }
}
