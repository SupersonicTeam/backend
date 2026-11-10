package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * Convite de equipe inválido, vencido, já usado ou dirigido a outro e-mail (issue #228) — HTTP
 * 400. Uma mensagem só para todos os casos: o token não confirma nada a quem o tem.
 */
public class ConviteEquipeInvalidoException extends DomainException {

  private static final String MENSAGEM = "Convite de equipe inválido ou expirado.";

  /** Cria a exceção. */
  public ConviteEquipeInvalidoException() {
    super(HttpStatus.BAD_REQUEST, MENSAGEM, List.of(new ErrorDetail(
        ErrorCode.CONVITE_EQUIPE_INVALIDO.code(),
        "token",
        MENSAGEM,
        "Peça um convite novo ao administrador do escritório; ele vale 7 dias e uma vez só.",
        null,
        null)));
  }
}
