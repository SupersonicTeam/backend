package br.com.agrocontabil.model;

/**
 * Quem tem a cota semanal da Ceres (issue #166). Hoje só o produtor conversa com a Ceres; quando o
 * contador puder perguntar sobre um produtor da carteira, ele consome a cota do escritório, não a
 * do produtor.
 */
public enum TipoTitularCota {
  /** O próprio produtor rural. */
  PRODUTOR,
  /** O escritório de contabilidade, compartilhada pelos contadores. */
  ESCRITORIO
}
