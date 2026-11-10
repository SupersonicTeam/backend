package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import br.com.agrocontabil.model.StatusNotaFiscal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * Lançada quando se tenta sincronizar o status na SEFAZ de uma nota que não está aguardando
 * desfecho — ou seja, que não está em {@link StatusNotaFiscal#PENDENTE_SEFAZ}. HTTP 409 Conflict.
 *
 * <p>A sincronização só faz sentido para notas transmitidas e pendentes de retorno do fisco.
 * Notas em estado terminal ({@code AUTORIZADA}/{@code REJEITADA}/{@code CANCELADA}) já têm
 * desfecho definitivo; notas pré-transmissão ({@code RASCUNHO}/{@code XML_GERADO}/
 * {@code COM_INCONSISTENCIAS}) ainda não foram enviadas. A mensagem/sugestão ao cliente reflete
 * qual dos dois casos ocorreu, para não induzir a ação errada.
 */
public class NotaFiscalNaoConsultavelException extends DomainException {

  /**
   * Cria a exceção descrevendo a nota e o status inválido para sincronização, com uma sugestão
   * coerente com o status (pré-transmissão vs. terminal).
   *
   * @param id     identificador da nota fiscal
   * @param status status atual da nota
   */
  public NotaFiscalNaoConsultavelException(UUID id, StatusNotaFiscal status) {
    super(
        HttpStatus.CONFLICT,
        "Nota fiscal '%s' no status '%s' não permite sincronização de status com a SEFAZ. "
            .formatted(id, status)
            + "Apenas notas pendentes de retorno (PENDENTE_SEFAZ) podem ser sincronizadas.",
        List.of(ErrorDetail.of(ErrorCode.NOTA_NAO_CONSULTAVEL, sugestaoPara(status))));
  }

  /** Sugestão coerente com o status: transmitir (pré-envio) ou nada a fazer (desfecho definitivo). */
  private static String sugestaoPara(StatusNotaFiscal status) {
    return switch (status) {
      case AUTORIZADA, REJEITADA, CANCELADA ->
          "A nota já tem um desfecho definitivo na SEFAZ; não há status pendente a sincronizar.";
      default ->
          "Gere o XML e envie a nota à SEFAZ (enviar-sefaz) antes de sincronizar o status.";
    };
  }
}
