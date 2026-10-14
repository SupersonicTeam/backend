package br.com.agrocontabil.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/**
 * Uso da Ceres na semana (issue #166), para a barra de uso do chat. Em créditos, nunca tokens.
 *
 * @param plano          nome do plano
 * @param habilitada     {@code false} com a cota desligada (nada é bloqueado)
 * @param creditosSemana limite da semana
 * @param consumidos     créditos gastos na semana
 * @param restantes      créditos ainda disponíveis (descontadas as perguntas em andamento)
 * @param renovaEm       próxima segunda-feira 00:00, horário de Brasília
 */
@Schema(description = "Uso semanal da Ceres, em creditos")
public record AssistenteUsoResponse(
    @Schema(example = "padrao") String plano,
    boolean habilitada,
    @Schema(example = "1000") long creditosSemana,
    @Schema(example = "120") long consumidos,
    @Schema(example = "880") long restantes,
    OffsetDateTime renovaEm) {}
