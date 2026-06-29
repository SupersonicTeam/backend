package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import br.com.agrocontabil.model.InvoiceStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * Lançada quando se tenta consultar o status na SEFAZ de uma invoice que ainda não foi
 * transmitida (sem key de acesso / em RASCUNHO). HTTP 409 Conflict.
 *
 * <p>Só faz sentido consultar o status de notas que já possuem key de acesso e foram
 * enviadas ao fisco (XML_GERADO em diante).
 */
public class InvoiceNotQueryableException extends DomainException {

  /**
   * Cria a exceção descrevendo a invoice e o status inválido para consulta.
   *
   * @param id     identificador da invoice fiscal
   * @param status status atual da invoice
   */
  public InvoiceNotQueryableException(UUID id, InvoiceStatus status) {
    super(
        HttpStatus.CONFLICT,
        "Nota fiscal '%s' nao pode ter o status consultado na SEFAZ no status '%s'. "
            .formatted(id, status)
            + "Apenas notas ja transmitidas (com chave de acesso) podem ser consultadas.",
        List.of(ErrorDetail.of(ErrorCode.NOTA_NAO_CONSULTAVEL,
            "Gere o XML e envie a nota a SEFAZ antes de consultar o status.")));
  }
}
