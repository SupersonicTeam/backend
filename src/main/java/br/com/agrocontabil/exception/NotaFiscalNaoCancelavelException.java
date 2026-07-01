package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import br.com.agrocontabil.model.StatusNotaFiscal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * Lançada quando se tenta cancelar uma nota cujo status não é
 * {@link StatusNotaFiscal#AUTORIZADA}. HTTP 409 Conflict (issue #21).
 *
 * <p>Somente notas autorizadas pela SEFAZ podem ser canceladas. Notas em RASCUNHO,
 * XML_GERADO, REJEITADA, PENDENTE_SEFAZ ou já CANCELADA não são canceláveis.
 */
public class NotaFiscalNaoCancelavelException extends DomainException {

  /**
   * Cria a exceção descrevendo a nota e o status inválido para cancelamento.
   *
   * @param id     identificador da nota fiscal
   * @param status status atual da nota (diferente de AUTORIZADA)
   */
  public NotaFiscalNaoCancelavelException(UUID id, StatusNotaFiscal status) {
    super(
        HttpStatus.CONFLICT,
        "Nota fiscal '%s' nao pode ser cancelada no status '%s'. "
            .formatted(id, status)
            + "Somente notas AUTORIZADAS pela SEFAZ podem ser canceladas.",
        List.of(ErrorDetail.of(ErrorCode.NOTA_NAO_CANCELAVEL,
            "Apenas notas em status AUTORIZADA podem ser canceladas.")));
  }
}
