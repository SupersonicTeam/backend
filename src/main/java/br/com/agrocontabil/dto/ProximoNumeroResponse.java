package br.com.agrocontabil.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Número sugerido para a próxima nota própria de uma série (issue #539). É só sugestão, sem
 * reserva.
 *
 * @param serie  série consultada
 * @param numero maior número das notas próprias da série mais 1; {@code 1} sem nenhuma
 */
@Schema(description = "Próximo número sugerido para a nota própria da série (sem reserva)")
public record ProximoNumeroResponse(
    @Schema(description = "Série consultada", example = "1") int serie,
    @Schema(description = "Maior número das notas próprias da série mais 1", example = "3185")
    long numero) {
}
