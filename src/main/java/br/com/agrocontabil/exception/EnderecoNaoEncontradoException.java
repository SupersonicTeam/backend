package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * O endereço de escritório pedido não é servido (issue #464) — HTTP 404.
 *
 * <p>A mesma resposta para slug inexistente, mal formado, reservado, de escritório sem CNPJ, com a
 * carência vencida ou de escritório apagado: nada nela diz qual dos casos é, e o slug não volta no
 * {@code rejectedValue}.
 */
public class EnderecoNaoEncontradoException extends DomainException {

  private static final String MENSAGEM = "Endereço não encontrado.";

  /** Cria a exceção. */
  public EnderecoNaoEncontradoException() {
    super(HttpStatus.NOT_FOUND, MENSAGEM, List.of(new ErrorDetail(
        ErrorCode.ENDERECO_NAO_ENCONTRADO.code(),
        null,
        MENSAGEM,
        "Entre por www.agroconia.com.br.",
        null,
        null)));
  }
}
