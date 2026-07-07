package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * Lançada quando se tenta marcar como paga uma guia fiscal que já está
 * {@link br.com.agrocontabil.model.StatusGuiaFiscal#PAGA}. HTTP 409 Conflict (issue #74).
 */
public class GuiaJaPagaException extends DomainException {

  /**
   * Cria a exceção descrevendo a guia já paga.
   *
   * @param id identificador da guia fiscal
   */
  public GuiaJaPagaException(UUID id) {
    super(
        HttpStatus.CONFLICT,
        "Guia fiscal '%s' ja esta marcada como paga.".formatted(id),
        List.of(ErrorDetail.of(ErrorCode.GUIA_JA_PAGA,
            "Esta guia fiscal ja foi marcada como paga anteriormente.")));
  }
}
