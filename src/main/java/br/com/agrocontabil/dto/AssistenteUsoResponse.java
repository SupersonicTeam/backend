package br.com.agrocontabil.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/**
 * Uso da Ceres na semana (issue #166), para a barra de uso do chat. A tela mostra só o
 * {@code percentualUsado} (#511), sempre arredondado; os campos em créditos ficam para
 * compatibilidade e suporte. Nunca tokens.
 *
 * @param plano          nome do plano
 * @param habilitada     {@code false} com a cota desligada (nada é bloqueado)
 * @param creditosSemana limite da semana
 * @param consumidos     créditos gastos na semana
 * @param restantes      créditos ainda disponíveis (descontadas as perguntas em andamento)
 * @param renovaEm       próxima segunda-feira 00:00, horário de Brasília
 * @param percentualUsado percentual da semana usado, inteiro de 0 a 100
 */
@Schema(description = "Uso semanal da Ceres: a tela mostra so o percentualUsado")
public record AssistenteUsoResponse(
    @Schema(example = "padrao") String plano,
    boolean habilitada,
    @Schema(example = "1000000") long creditosSemana,
    @Schema(example = "120000") long consumidos,
    @Schema(example = "880000") long restantes,
    OffsetDateTime renovaEm,
    @Schema(description = "Percentual da semana ja usado, o unico numero de uso que a tela "
        + "mostra: teto(consumidos / creditosSemana x 100), no maximo 99 enquanto houver saldo, "
        + "100 so sem saldo e 0 so sem uso nenhum", minimum = "0", maximum = "100",
        example = "12") int percentualUsado) {}
