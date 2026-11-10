package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * Lançada quando a SEFAZ <strong>recusa</strong> o evento de cancelamento (issues #84 e #96).
 *
 * <p>Antes, esse caso reaproveitava {@link NotaFiscalNaoCancelavelException}, cuja semântica é
 * "o status atual não permite cancelar" — e a mensagem saía se contradizendo, apontando
 * {@code AUTORIZADA}, que é exatamente o status cancelável. O produtor tentava de novo, recebia o
 * mesmo erro e nunca sabia que a recusa vinha do fisco nem por quê.
 *
 * <p>Contrato do {@link ErrorDetail} (issue #84):
 * <ul>
 *   <li>{@code code} = {@code CANCELAMENTO_REJEITADO};</li>
 *   <li>{@code message} = o {@code xMotivo} devolvido pela SEFAZ, sem prefixo — é informação do
 *       próprio fisco sobre o documento do produtor, não detalhe técnico interno. Se o fisco não
 *       mandar motivo, um texto genérico com o cStat;</li>
 *   <li>{@code rejectedValue} = o {@code cStat} do evento.</li>
 * </ul>
 *
 * <p>HTTP <strong>422</strong>, como {@link CancelamentoForaPrazoException}: a requisição é válida
 * e a nota está no status certo, mas uma regra fiscal (ex.: cStat 420, NF-e com CT-e vinculado)
 * impede o cancelamento. Não é 409 — a nota continua
 * {@link br.com.agrocontabil.model.StatusNotaFiscal#AUTORIZADA} e não há conflito de estado local.
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
        HttpStatus.UNPROCESSABLE_ENTITY,
        "A SEFAZ recusou o cancelamento da nota fiscal '%s' (cStat %s).".formatted(id, cstat),
        List.of(new ErrorDetail(
            ErrorCode.CANCELAMENTO_REJEITADO.code(),
            null,
            xmotivo != null && !xmotivo.isBlank()
                ? xmotivo
                : "A SEFAZ recusou o cancelamento (cStat " + cstat + ").",
            "Resolva a pendência apontada pela SEFAZ e tente novamente. "
                + "A nota permanece autorizada.",
            cstat,
            null)));
  }
}
