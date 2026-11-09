package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import java.time.YearMonth;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * Lançada quando se tenta reabrir uma apuração cujo período está fora do limite de 3 meses
 * retroativos em relação ao mês atual (RN005). HTTP 422 Unprocessable Entity
 * (issue Agrocontabil#42).
 *
 * <p>O limite é <strong>inclusivo</strong>: a reabertura é permitida até o 3º mês retroativo
 * (ex.: com o mês atual em {@code 2026-07}, o período mais antigo reabrível é {@code 2026-04}).
 * Fuso de referência {@code America/Sao_Paulo}.
 */
public class ReaberturaForaPrazoException extends DomainException {

  /**
   * Cria a exceção descrevendo a apuração, seu período e o período-limite retroativo.
   *
   * @param periodo        período da apuração (fora do prazo)
   * @param periodoLimite  período mais antigo ainda reabrível (3º mês retroativo)
   */
  public ReaberturaForaPrazoException(YearMonth periodo, YearMonth periodoLimite) {
    super(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "Apuracao do periodo %s nao pode ser reaberta: "
            .formatted(periodo)
            + "a reabertura so e permitida ate 3 meses retroativos "
            + "(periodo mais antigo reabrivel: %s) — RN005.".formatted(periodoLimite),
        List.of(new ErrorDetail(
            ErrorCode.REABERTURA_FORA_PRAZO.code(),
            null,
            "A reabertura so e permitida para periodos dentro dos ultimos 3 meses (RN005). "
                + "Periodo mais antigo reabrivel: " + periodoLimite + ".",
            "Periodos anteriores devem ser tratados por outro procedimento fiscal.",
            periodo.toString(),
            "a partir de " + periodoLimite)));
  }
}
