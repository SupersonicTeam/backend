package br.com.agrocontabil.exception;

import br.com.agrocontabil.model.StatusNotaFiscal;
import java.util.UUID;

/**
 * Lancada quando uma operacao de edicao ou exclusao e tentada em uma nota fiscal
 * cujo status nao e {@link StatusNotaFiscal#RASCUNHO}.
 *
 * <p>Mapeada para HTTP 409 Conflict pelo {@code GlobalExceptionHandler}, pois indica
 * um conflito de estado de negocio — a nota existe, mas nao pode ser modificada
 * no estado atual.
 */
public class NotaFiscalNaoEditavelException extends RuntimeException {

  /**
   * Cria a excecao informando o ID da nota e o status que impediu a operacao.
   *
   * @param id     identificador da nota fiscal
   * @param status status atual da nota
   */
  public NotaFiscalNaoEditavelException(UUID id, StatusNotaFiscal status) {
    super(
        "Nota fiscal '%s' nao pode ser editada ou excluida no status '%s'. "
            .formatted(id, status)
            + "Somente notas em status RASCUNHO podem ser alteradas.");
  }
}
