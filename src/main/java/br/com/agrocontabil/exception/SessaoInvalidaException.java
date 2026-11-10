package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * O refresh token não renova a sessão (issue #207). HTTP 401 {@code SESSAO_INVALIDA}.
 *
 * <p>Uma resposta só para todos os motivos — ausente, desconhecido, expirado, revogado, reusado,
 * credencial desativada ou com a senha trocada. Dizer "reusado" avisaria o ladrão de que foi
 * descoberto; dizer "expirado" confirmaria que o token um dia valeu.
 */
public class SessaoInvalidaException extends DomainException {

  private static final String MENSAGEM = "Sessão expirada ou encerrada.";

  private static final String SUGESTAO = "Entre de novo com e-mail e senha em POST /api/auth/login.";

  public SessaoInvalidaException() {
    super(
        HttpStatus.UNAUTHORIZED,
        MENSAGEM,
        List.of(new ErrorDetail(
            ErrorCode.SESSAO_INVALIDA.code(), null, MENSAGEM, SUGESTAO, null, null)));
  }
}
