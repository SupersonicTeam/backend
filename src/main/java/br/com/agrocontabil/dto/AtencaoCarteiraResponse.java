package br.com.agrocontabil.dto;

/**
 * O que pede atenção num cliente da carteira (issue #177), calculado por consultas agregadas.
 *
 * @param notasComInconsistencia   notas em {@code COM_INCONSISTENCIAS}
 * @param notasAguardandoAprovacao notas esperando o aval do escritório (fluxo da issue #178; zero
 *                                 até lá)
 * @param guiasVencidas            guias vencidas e não pagas
 * @param vencimentosProximos7Dias guias pendentes que vencem de hoje a 7 dias
 */
public record AtencaoCarteiraResponse(
    long notasComInconsistencia,
    long notasAguardandoAprovacao,
    long guiasVencidas,
    long vencimentosProximos7Dias) {

  /** Algum indicador acima de zero. */
  public boolean precisaAtencao() {
    return notasComInconsistencia + notasAguardandoAprovacao + guiasVencidas
        + vencimentosProximos7Dias > 0;
  }
}
