package br.com.agrocontabil.model;

/** Faixa de urgência de um item notificado (issue #182, decisão do Murillo). */
public enum UrgenciaVencimento {
  /** Vence hoje ou amanhã. */
  URGENTE,
  /** Vence de 2 a 7 dias. */
  PROXIMO,
  /** Guia vencida (ou pendente com data passada) ou pedido de documento com prazo passado. */
  ATRASADO
}
