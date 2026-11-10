package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import br.com.agrocontabil.model.StatusApuracao;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * Lançada quando se tenta reabrir uma apuração cujo status não é
 * {@link StatusApuracao#FECHADO}. HTTP 409 Conflict (issue Agrocontabil#42 — RN005).
 *
 * <p>Somente apurações FECHADAS podem ser reabertas. Apurações em ABERTO ou EM_PROCESSAMENTO
 * não têm o que reabrir.
 */
public class ApuracaoNaoReabrivelException extends DomainException {

  /**
   * Cria a exceção descrevendo a apuração e o status inválido para reabertura.
   *
   * @param status status atual (diferente de FECHADO)
   */
  public ApuracaoNaoReabrivelException(StatusApuracao status) {
    super(
        HttpStatus.CONFLICT,
        "Apuração não pode ser reaberta no status '%s'. "
            .formatted(status)
            + "Somente apurações FECHADAS podem ser reabertas.",
        List.of(ErrorDetail.of(ErrorCode.APURACAO_NAO_REABRIVEL,
            "Apenas apurações em status FECHADO podem ser reabertas.")));
  }
}
