package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * Lançada quando se tenta cancelar uma NF-e após o prazo legal de 30 dias corridos
 * contados da emissão (RN006). HTTP 422 Unprocessable Entity (issue #21).
 *
 * <p>O limite é <strong>inclusivo</strong>: o cancelamento é permitido até o 30º dia
 * corrido após a emissão (fuso {@code America/Sao_Paulo}).
 */
public class CancelamentoForaPrazoException extends DomainException {

  private static final DateTimeFormatter DATA =
      DateTimeFormatter.ofPattern("yyyy-MM-dd");

  /**
   * Cria a exceção descrevendo a nota e a data-limite de cancelamento excedida.
   *
   * @param id          identificador da nota fiscal
   * @param dataLimite  último instante em que o cancelamento seria permitido
   */
  public CancelamentoForaPrazoException(UUID id, OffsetDateTime dataLimite) {
    super(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "Nota fiscal '%s' não pode ser cancelada: o prazo legal de 30 dias da emissão "
            .formatted(id)
            + "expirou em %s (RN006).".formatted(DATA.format(dataLimite)),
        List.of(new ErrorDetail(
            ErrorCode.CANCELAMENTO_FORA_PRAZO.code(),
            null,
            "O cancelamento só é permitido dentro de 30 dias corridos da emissão (RN006). "
                + "Prazo expirado em " + DATA.format(dataLimite) + ".",
            "Notas fora do prazo devem ser tratadas por outro procedimento fiscal.",
            null,
            null)));
  }
}
