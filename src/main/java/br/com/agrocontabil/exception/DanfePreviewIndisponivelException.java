package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import br.com.agrocontabil.model.StatusNotaFiscal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * Preview da DANFE pedido para uma nota que já saiu do rascunho (issue #89). HTTP 409.
 *
 * <p>O preview existe para conferir a nota antes de emitir. Depois do XML gerado vale a DANFE de
 * verdade ({@code GET /api/notas/{id}/pdf}), e um PDF "sem valor fiscal" de uma nota já emitida só
 * criaria dúvida sobre qual documento vale.
 */
public class DanfePreviewIndisponivelException extends DomainException {

  /**
   * Cria a exceção para a nota e o status em que o preview não se aplica.
   *
   * @param id     identificador da nota fiscal
   * @param status status atual da nota
   */
  public DanfePreviewIndisponivelException(UUID id, StatusNotaFiscal status) {
    super(
        HttpStatus.CONFLICT,
        "Preview da DANFE indisponível para a nota '%s' no status '%s'.".formatted(id, status),
        List.of(new ErrorDetail(
            ErrorCode.DANFE_PREVIEW_INDISPONIVEL.code(),
            null,
            "O preview só existe antes da emissão (RASCUNHO ou COM_INCONSISTENCIAS).",
            "Para nota com XML gerado, baixe a DANFE em GET /api/notas/{id}/pdf.",
            status.name(),
            "RASCUNHO, COM_INCONSISTENCIAS")));
  }
}
