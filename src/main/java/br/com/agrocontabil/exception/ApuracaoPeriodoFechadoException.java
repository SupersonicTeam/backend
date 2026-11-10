package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import br.com.agrocontabil.model.StatusApuracao;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * Lançada quando a consolidação tenta rebaixar um período já {@link StatusApuracao#FECHADO} para
 * {@link StatusApuracao#ABERTO}. HTTP 409 Conflict (issue Agrocontabil#54 — protege a RN005).
 *
 * <p>A transição FECHADO → ABERTO tem um único ponto de entrada: a reabertura auditada
 * ({@code ApuracaoService.reabrir}), que aplica o limite de 3 meses e registra quem/quando. A
 * consolidação (sob demanda ou pelo job) nunca pode reabrir um período por vias transversas, sem
 * trilha de auditoria nem checagem de prazo.
 */
public class ApuracaoPeriodoFechadoException extends DomainException {

  /**
   * Cria a exceção descrevendo o período fechado que não pode ser consolidado/rebaixado.
   *
   * @param periodo período no formato {@code YYYY-MM}
   */
  public ApuracaoPeriodoFechadoException(String periodo) {
    super(
        HttpStatus.CONFLICT,
        "Período '%s' está FECHADO e não pode ser consolidado.".formatted(periodo)
            + " Use a reabertura auditada para reabri-lo antes de recalcular.",
        List.of(ErrorDetail.of(ErrorCode.CONSOLIDACAO_PERIODO_FECHADO,
            "Período FECHADO só pode voltar a ABERTO pela reabertura auditada (RN005).")));
  }
}
