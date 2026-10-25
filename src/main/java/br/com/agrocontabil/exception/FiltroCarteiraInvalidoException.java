package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import java.util.Collection;
import java.util.List;
import org.springframework.http.HttpStatus;

/** Ordenação de {@code GET /api/contador/produtores} fora da lista (issue #177) — HTTP 400. */
public class FiltroCarteiraInvalidoException extends DomainException {

  /**
   * Cria a exceção.
   *
   * @param ordenacao  valor pedido
   * @param permitidas valores aceitos
   */
  public FiltroCarteiraInvalidoException(String ordenacao, Collection<String> permitidas) {
    super(HttpStatus.BAD_REQUEST, "Ordenacao nao permitida: " + ordenacao + ".",
        List.of(new ErrorDetail(
            ErrorCode.FILTRO_CARTEIRA_INVALIDO.code(),
            "sort",
            "Ordenacao nao permitida: " + ordenacao + ".",
            "Ordene por: " + String.join(", ", permitidas) + ".",
            ordenacao,
            String.join(", ", permitidas))));
  }
}
