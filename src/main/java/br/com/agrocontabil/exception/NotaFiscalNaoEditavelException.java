package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import br.com.agrocontabil.model.StatusNotaFiscal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * Lançada quando edição/exclusão é tentada em nota cujo status não é RASCUNHO. HTTP 409.
 */
public class NotaFiscalNaoEditavelException extends DomainException {

  public NotaFiscalNaoEditavelException(UUID id, StatusNotaFiscal status) {
    super(
        HttpStatus.CONFLICT,
        "Nota fiscal '%s' nao pode ser editada ou excluida no status '%s'. "
            .formatted(id, status)
            + "Somente notas em status RASCUNHO podem ser alteradas.",
        List.of(ErrorDetail.of(ErrorCode.NOTA_NAO_EDITAVEL,
            "Somente notas em RASCUNHO podem ser alteradas.")));
  }
}
