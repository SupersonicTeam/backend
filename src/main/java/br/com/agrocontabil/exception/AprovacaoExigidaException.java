package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import br.com.agrocontabil.model.StatusNotaFiscal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * O produtor tem contador vinculado e a nota ainda não foi aprovada (issue #178) — HTTP 409. O
 * vínculo é conferido no momento do {@code gerar-xml}.
 */
public class AprovacaoExigidaException extends DomainException {

  /**
   * Cria a exceção.
   *
   * @param notaId nota
   * @param atual  status atual
   */
  public AprovacaoExigidaException(UUID notaId, StatusNotaFiscal atual) {
    super(HttpStatus.CONFLICT, mensagem(notaId), List.of(new ErrorDetail(
        ErrorCode.APROVACAO_EXIGIDA.code(), "status", mensagem(notaId),
        "Envie a nota para revisao (POST /api/notas/{id}/enviar-para-revisao) e aguarde a "
            + "aprovacao do contador.",
        atual.name(), StatusNotaFiscal.APROVADA.name())));
  }

  private static String mensagem(UUID notaId) {
    return "A nota " + notaId + " precisa da aprovacao do contador antes da emissao.";
  }
}
