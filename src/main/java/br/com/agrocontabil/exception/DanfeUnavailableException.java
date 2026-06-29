package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import br.com.agrocontabil.model.InvoiceStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * Lançada quando se solicita a DANFE (PDF) de uma invoice que ainda não gerou o XML
 * (em RASCUNHO, sem key de acesso). HTTP 409 Conflict.
 *
 * <p>A DANFE é o documento auxiliar da NF-e: só existe após a geração do XML.
 */
public class DanfeUnavailableException extends DomainException {

  /**
   * Cria a exceção descrevendo a invoice e o status inválido para emissão da DANFE.
   *
   * @param id     identificador da invoice fiscal
   * @param status status atual da invoice
   */
  public DanfeUnavailableException(UUID id, InvoiceStatus status) {
    super(
        HttpStatus.CONFLICT,
        "DANFE indisponivel para a nota '%s' no status '%s'. "
            .formatted(id, status)
            + "Gere o XML da nota antes de baixar a DANFE.",
        List.of(ErrorDetail.of(ErrorCode.DANFE_INDISPONIVEL,
            "Gere o XML da nota (POST /api/notas/{id}/gerar-xml) antes de baixar a DANFE.")));
  }
}
