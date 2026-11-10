package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * Lançada quando o produtor tenta cancelar uma compra importada (nota {@code IMPORTADA}): o
 * evento de cancelamento é do emitente, o fornecedor. HTTP 409 Conflict (issue #418).
 *
 * <p>Nada é transmitido à SEFAZ e o status da nota não muda.
 */
public class CancelamentoCompraImportadaException extends DomainException {

  /**
   * Cria a exceção para a compra importada.
   *
   * @param id identificador da nota fiscal
   */
  public CancelamentoCompraImportadaException(UUID id) {
    super(
        HttpStatus.CONFLICT,
        "Nota fiscal '%s' é uma compra importada e não pode ser cancelada pelo destinatário."
            .formatted(id),
        List.of(ErrorDetail.of(ErrorCode.CANCELAMENTO_COMPRA_IMPORTADA,
            "Esta nota é uma compra: o cancelamento cabe ao fornecedor que a emitiu.")));
  }
}
