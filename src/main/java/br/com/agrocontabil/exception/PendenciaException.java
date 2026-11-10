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
 * recusado (400), teto de anexos (409) e, do antivírus (#227), anexo infectado (422) e em
 * verificação (409).
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
        "status", "A pendência " + id + " está " + atual + " e não permite esta ação.",
        "Recarregue a pendência: o status mudou ou não aceita esta ação.", atual.name(),
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
        motivo, "Envie um PDF, JPEG, PNG ou XML de até 5 MB.", null,
        "PDF, JPEG, PNG ou XML, até 5 MB");
  }

  /**
   * O antivírus achou ameaça no arquivo (#227); o conteúdo já foi descartado.
   *
   * @param assinatura nome da assinatura achada
   * @return a exceção (422)
   */
  public static PendenciaException anexoInfectado(String assinatura) {
    return new PendenciaException(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.ANEXO_INFECTADO,
        "arquivo", "O antivírus encontrou uma ameaça no arquivo; ele não foi guardado.",
        "Passe um antivírus no seu computador e envie o documento de novo, de preferência "
            + "gerado outra vez a partir do original.", assinatura, null);
  }

  /**
   * O anexo ainda não passou pelo antivírus (#227).
   *
   * @param anexoId anexo
   * @return a exceção (409)
   */
  public static PendenciaException anexoEmVerificacao(UUID anexoId) {
    return new PendenciaException(HttpStatus.CONFLICT, ErrorCode.ANEXO_EM_VERIFICACAO, "anexoId",
        "O anexo " + anexoId + " ainda está em verificação de antivírus.",
        "Tente baixar de novo em alguns minutos.", "EM_VERIFICACAO", "LIMPO");
  }

  /**
   * A pendência já tem o máximo de anexos.
   *
   * @param maximo teto
   * @return a exceção (409)
   */
  public static PendenciaException limiteAnexos(int maximo) {
    return new PendenciaException(HttpStatus.CONFLICT, ErrorCode.LIMITE_ANEXOS, "arquivo",
        "A pendência já tem " + maximo + " anexos.",
        "Junte os documentos num PDF só, ou peça ao escritório para revisar os enviados.", null,
        String.valueOf(maximo));
  }
}
