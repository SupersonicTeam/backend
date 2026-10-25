package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import java.util.Collection;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * Ordenação ou filtro de {@code GET /api/contador/produtores} fora da lista (issues #177 e #228)
 * — HTTP 400.
 */
public class FiltroCarteiraInvalidoException extends DomainException {

  /**
   * Cria a exceção.
   *
   * @param campo      parâmetro recusado ({@code sort}, {@code responsavel})
   * @param valor      valor pedido
   * @param permitidas valores aceitos
   */
  public FiltroCarteiraInvalidoException(String campo, String valor,
      Collection<String> permitidas) {
    super(HttpStatus.BAD_REQUEST, "Valor nao permitido em " + campo + ": " + valor + ".",
        List.of(new ErrorDetail(
            ErrorCode.FILTRO_CARTEIRA_INVALIDO.code(),
            campo,
            "Valor nao permitido em " + campo + ": " + valor + ".",
            "Use um destes: " + String.join(", ", permitidas) + ".",
            valor,
            String.join(", ", permitidas))));
  }
}
