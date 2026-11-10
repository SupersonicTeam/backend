package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import br.com.agrocontabil.model.StatusNotaFiscal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * Lançada quando se tenta enviar à SEFAZ uma nota cujo status não é
 * {@link StatusNotaFiscal#XML_GERADO}. HTTP 409 Conflict.
 *
 * <p>Somente notas com XML gerado e assinado podem ser transmitidas. Notas em RASCUNHO
 * precisam gerar o XML primeiro (POST /{id}/gerar-xml); notas já AUTORIZADAS não devem
 * ser reenviadas.
 */
public class NotaFiscalNaoEnviavelException extends DomainException {

  /**
   * Cria a exceção descrevendo a nota e o status inválido para envio.
   *
   * @param id     identificador da nota fiscal
   * @param status status atual da nota (diferente de XML_GERADO)
   */
  public NotaFiscalNaoEnviavelException(UUID id, StatusNotaFiscal status) {
    super(
        HttpStatus.CONFLICT,
        "Nota fiscal '%s' não pode ser enviada à SEFAZ no status '%s'. "
            .formatted(id, status)
            + "Somente notas em status XML_GERADO podem ser transmitidas.",
        List.of(ErrorDetail.of(ErrorCode.NOTA_NAO_ENVIAVEL,
            "Gere o XML da nota (POST /api/notas/{id}/gerar-xml) antes de enviar à SEFAZ.")));
  }
}
