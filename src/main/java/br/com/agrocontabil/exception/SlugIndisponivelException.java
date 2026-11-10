package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * O slug pedido para o endereço do escritório não está disponível (issue #464) — HTTP 409.
 *
 * <p>A mesma resposta para slug reservado, em uso por outro escritório ou em carência: um texto
 * diferente para cada caso diria quem tem qual endereço. Sem {@code rejectedValue}.
 */
public class SlugIndisponivelException extends DomainException {

  private static final String MENSAGEM = "Este endereço não está disponível.";

  /** Cria a exceção. */
  public SlugIndisponivelException() {
    super(HttpStatus.CONFLICT, MENSAGEM, List.of(new ErrorDetail(
        ErrorCode.SLUG_INDISPONIVEL.code(),
        "slug",
        MENSAGEM,
        "Escolha outro endereço, por exemplo com o nome da cidade ou da região.",
        null,
        null)));
  }
}
