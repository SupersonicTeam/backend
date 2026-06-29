package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import br.com.agrocontabil.model.InvoiceStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * Lançada quando edição/exclusão é tentada em invoice cujo status não é RASCUNHO. HTTP 409.
 */
public class InvoiceNotEditableException extends DomainException {

  public InvoiceNotEditableException(UUID id, InvoiceStatus status) {
    super(
        HttpStatus.CONFLICT,
        "Nota fiscal '%s' nao pode ser editada ou excluida no status '%s'. "
            .formatted(id, status)
            + "Somente notas em status RASCUNHO podem ser alteradas.",
        List.of(ErrorDetail.of(ErrorCode.NOTA_NAO_EDITAVEL,
            "Somente notas em RASCUNHO podem ser alteradas.")));
  }

  /** Construtor de mensagem livre, para variacoes de reason (ex.: cancelamento). HTTP 409. */
  private InvoiceNotEditableException(String message) {
    super(
        HttpStatus.CONFLICT,
        message,
        List.of(ErrorDetail.of(ErrorCode.NOTA_NAO_EDITAVEL, message)));
  }

  /**
   * Cria a excecao para tentativa de cancelamento de invoice fora do status AUTORIZADA.
   *
   * @param id     identificador da invoice
   * @param status status atual
   * @return excecao com mensagem especifica de cancelamento (HTTP 409)
   */
  public static InvoiceNotEditableException naoCancelavel(UUID id, InvoiceStatus status) {
    return new InvoiceNotEditableException(
        "Nota fiscal '%s' nao pode ser cancelada no status '%s'. "
            .formatted(id, status)
            + "Somente notas AUTORIZADAS podem ser canceladas.");
  }
}
