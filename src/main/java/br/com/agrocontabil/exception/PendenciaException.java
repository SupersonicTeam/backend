package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import br.com.agrocontabil.model.StatusPendencia;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * Recusas do fluxo de pendências e anexos (issue #180): transição inválida (409), anexo
 * recusado (400) e teto de anexos (409).
 */
public class PendenciaException extends DomainException {

  private PendenciaException(HttpStatus status, ErrorCode codigo, String campo, String mensagem,
      String sugestao, String rejeitado, String esperado) {
    super(status, mensagem, List.of(new ErrorDetail(codigo.code(), campo, mensagem, sugestao,
        rejeitado, esperado)));
  }

  /**
   * O status atual não aceita a ação.
   *
   * @param id         pendência
   * @param atual      status atual
   * @param permitidos status a partir dos quais vale
   * @return a exceção (409)
   */
  public static PendenciaException transicao(UUID id, StatusPendencia atual,
      Collection<StatusPendencia> permitidos) {
    return new PendenciaException(HttpStatus.CONFLICT, ErrorCode.TRANSICAO_PENDENCIA_INVALIDA,
        "status", "A pendencia " + id + " esta " + atual + " e nao permite esta acao.",
        "Recarregue a pendencia: o status mudou ou nao aceita esta acao.", atual.name(),
        permitidos.toString());
  }

  /**
   * Arquivo recusado.
   *
   * @param motivo o que está errado
   * @return a exceção (400)
   */
  public static PendenciaException anexoInvalido(String motivo) {
    return new PendenciaException(HttpStatus.BAD_REQUEST, ErrorCode.ANEXO_INVALIDO, "arquivo",
        motivo, "Envie um PDF, JPEG, PNG ou XML de ate 5 MB.", null,
        "PDF, JPEG, PNG ou XML, ate 5 MB");
  }

  /**
   * A pendência já tem o máximo de anexos.
   *
   * @param maximo teto
   * @return a exceção (409)
   */
  public static PendenciaException limiteAnexos(int maximo) {
    return new PendenciaException(HttpStatus.CONFLICT, ErrorCode.LIMITE_ANEXOS, "arquivo",
        "A pendencia ja tem " + maximo + " anexos.",
        "Junte os documentos num PDF so, ou peca ao escritorio para revisar os enviados.", null,
        String.valueOf(maximo));
  }
}
