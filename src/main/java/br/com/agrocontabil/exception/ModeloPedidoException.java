package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * Recusas do catálogo de modelos de pedido (issue #517): título repetido no escritório e teto de
 * modelos, ambos 409. O título nunca vai à mensagem nem ao {@code rejectedValue}: o texto é do
 * escritório e pode citar o cliente.
 */
public class ModeloPedidoException extends DomainException {

  private ModeloPedidoException(ErrorCode codigo, String campo, String mensagem, String sugestao,
      String esperado) {
    super(HttpStatus.CONFLICT, mensagem, List.of(new ErrorDetail(codigo.code(), campo, mensagem,
        sugestao, null, esperado)));
  }

  /**
   * Já existe modelo com o mesmo título no escritório (sem acento nem caixa).
   *
   * @return a exceção (409)
   */
  public static ModeloPedidoException duplicado() {
    return new ModeloPedidoException(ErrorCode.MODELO_PEDIDO_DUPLICADO, "titulo",
        "Já existe um modelo de pedido com este título no escritório.",
        "Use outro título ou edite o modelo que já existe. Acentos e maiúsculas não diferenciam "
            + "os títulos.", null);
  }

  /**
   * O escritório chegou ao teto de modelos.
   *
   * @param maximo teto
   * @return a exceção (409)
   */
  public static ModeloPedidoException limite(int maximo) {
    return new ModeloPedidoException(ErrorCode.LIMITE_MODELOS_PEDIDO, null,
        "O escritório já tem " + maximo + " modelos de pedido.",
        "Apague um modelo que não usa mais antes de criar outro.", String.valueOf(maximo));
  }
}
