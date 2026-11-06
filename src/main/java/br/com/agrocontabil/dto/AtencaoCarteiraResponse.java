package br.com.agrocontabil.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * O que pede atenção num cliente da carteira (issue #177), calculado por consultas agregadas.
 *
 * @param notasComInconsistencia   notas em {@code COM_INCONSISTENCIAS}
 * @param notasAguardandoAprovacao notas em {@code AGUARDANDO_APROVACAO} (issue #178)
 * @param guiasVencidas            guias vencidas e não pagas
 * @param vencimentosProximos7Dias guias pendentes que vencem de hoje a 7 dias
 * @param pendenciasEnviadas       pedidos de documento com anexo esperando o escritório (#180)
 */
@Schema(description = "Indicadores de atenção de um cliente da carteira")
public record AtencaoCarteiraResponse(
    @Schema(description = "Notas com inconsistências de validação", example = "2")
    long notasComInconsistencia,
    @Schema(description = "Notas aguardando aprovação do escritório", example = "1")
    long notasAguardandoAprovacao,
    @Schema(description = "Guias vencidas e não pagas", example = "0")
    long guiasVencidas,
    @Schema(description = "Guias que vencem nos próximos 7 dias", example = "1")
    long vencimentosProximos7Dias,
    @Schema(description = "Pedidos de documento com anexo a conferir", example = "1")
    long pendenciasEnviadas) {

  /** Algum indicador acima de zero. */
  public boolean precisaAtencao() {
    return notasComInconsistencia + notasAguardandoAprovacao + guiasVencidas
        + vencimentosProximos7Dias + pendenciasEnviadas > 0;
  }
}
