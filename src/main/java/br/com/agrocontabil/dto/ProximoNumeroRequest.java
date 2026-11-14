package br.com.agrocontabil.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Parâmetros de {@code GET /api/notas/proximo-numero} (issue #539).
 *
 * @param serie série da nota; ausente vale {@code 1}; de 0 a 999 (o mesmo limite do request da nota)
 */
@Schema(description = "Série para a qual se quer o próximo número da nota própria")
public record ProximoNumeroRequest(
    @Schema(description = "Série da nota (0 a 999); ausente = 1", example = "1",
        defaultValue = "1", minimum = "0", maximum = "999")
    @Min(0) @Max(999) Integer serie) {

  /** Série padrão quando o parâmetro não vem. */
  public static final int SERIE_PADRAO = 1;

  /** Série pedida, ou a padrão. */
  public int serieOuPadrao() {
    return serie == null ? SERIE_PADRAO : serie;
  }
}
