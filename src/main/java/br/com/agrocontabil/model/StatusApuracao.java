package br.com.agrocontabil.model;

/**
 * Ciclo de vida da {@link ApuracaoMensal} (issue Agrocontabil#41 — RN004).
 *
 * <p>Uma apuração nasce {@link #ABERTO} quando consolidada sob demanda pelo produtor, passa
 * transitoriamente por {@link #EM_PROCESSAMENTO} durante o recálculo e é {@link #FECHADO} pelo
 * job de fechamento no último dia do mês. Um período fechado deixa de acumular novas notas até
 * eventual reabertura (issue #42, RN005).
 */
public enum StatusApuracao {

  /** Período consolidado e ainda editável — acumula notas conforme surgem. */
  ABERTO,

  /** Estado transitório enquanto a consolidação é recalculada. */
  EM_PROCESSAMENTO,

  /** Período encerrado pelo fechamento automático; não acumula mais notas. */
  FECHADO
}
