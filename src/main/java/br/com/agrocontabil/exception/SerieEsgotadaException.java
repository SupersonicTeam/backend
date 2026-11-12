package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * A série já chegou ao maior número que o layout da NF-e aceita (999.999.999), então não há
 * próximo número nela (issue #539). HTTP 409.
 */
public class SerieEsgotadaException extends DomainException {

  /**
   * Cria a exceção.
   *
   * @param serie série esgotada
   */
  public SerieEsgotadaException(int serie) {
    super(HttpStatus.CONFLICT, "A série " + serie + " chegou ao último número possível.",
        List.of(new ErrorDetail(
            ErrorCode.SERIE_ESGOTADA.code(),
            "serie",
            "A série " + serie + " chegou ao último número possível (999999999).",
            "Use outra série para as próximas notas.",
            String.valueOf(serie),
            null)));
  }
}
