package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * Lançada quando a SEFAZ <strong>recusa</strong> o evento de cancelamento (issue #96).
 *
 * <p>Antes, esse caso reaproveitava {@link NotaFiscalNaoCancelavelException}, cuja semântica é
 * "o status atual não permite cancelar" — e a mensagem saía se contradizendo, apontando
 * {@code AUTORIZADA}, que é exatamente o status cancelável. O produtor tentava de novo, recebia o
 * mesmo erro e nunca sabia que a recusa vinha do fisco nem por quê.
 *
 * <p>Aqui a causa fica explícita e o {@code xMotivo} devolvido pela SEFAZ é repassado ao cliente:
 * é informação do próprio fisco sobre o documento do produtor, não detalhe técnico interno.
 *
 * <p>HTTP 409: a nota continua {@link br.com.agrocontabil.model.StatusNotaFiscal#AUTORIZADA} e
 * segue cancelável — o conflito é com o estado do evento no fisco, não com o da nota.
 */
public class CancelamentoRejeitadoException extends DomainException {

  /**
   * Cria a exceção com o código e o motivo retornados pela SEFAZ.
   *
   * @param id      identificador da nota fiscal
   * @param cstat   código de status do evento retornado pela SEFAZ (fora de {135, 155})
   * @param xmotivo descrição do motivo devolvida pela SEFAZ; pode ser nula
   */
  public CancelamentoRejeitadoException(UUID id, String cstat, String xmotivo) {
    super(
        HttpStatus.CONFLICT,
        "A SEFAZ recusou o cancelamento da nota fiscal '%s' (cStat %s).".formatted(id, cstat),
        List.of(new ErrorDetail(
            ErrorCode.CANCELAMENTO_REJEITADO.code(),
            null,
            xmotivo != null && !xmotivo.isBlank()
                ? "A SEFAZ recusou o cancelamento: " + xmotivo
                : "A SEFAZ recusou o cancelamento (cStat " + cstat + ").",
            "Confira a situacao da nota na SEFAZ antes de tentar novamente. "
                + "A nota permanece autorizada.",
            cstat,
            null)));
  }
}
