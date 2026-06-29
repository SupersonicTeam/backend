package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import br.com.agrocontabil.model.InvoiceStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * Lançada quando se tenta send à SEFAZ uma invoice cujo status não é
 * {@link InvoiceStatus#XML_GERADO}. HTTP 409 Conflict.
 *
 * <p>Somente notas com XML gerado e assinado podem ser transmitidas. Notas em RASCUNHO
 * precisam generate o XML primeiro (POST /{id}/generate-xml); notas já AUTORIZADAS não devem
 * ser reenviadas.
 */
public class InvoiceNotSubmittableException extends DomainException {

  /**
   * Cria a exceção descrevendo a invoice e o status inválido para envio.
   *
   * @param id     identificador da invoice fiscal
   * @param status status atual da invoice (diferente de XML_GERADO)
   */
  public InvoiceNotSubmittableException(UUID id, InvoiceStatus status) {
    super(
        HttpStatus.CONFLICT,
        "Nota fiscal '%s' nao pode ser enviada a SEFAZ no status '%s'. "
            .formatted(id, status)
            + "Somente notas em status XML_GERADO podem ser transmitidas.",
        List.of(ErrorDetail.of(ErrorCode.NOTA_NAO_ENVIAVEL,
            "Gere o XML da nota (POST /api/notas/{id}/gerar-xml) antes de enviar a SEFAZ.")));
  }
}
