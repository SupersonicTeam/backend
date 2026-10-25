package br.com.agrocontabil.model;

/**
 * O que fazer quando o prazo cai em sábado ou domingo (issue #181). Feriados ficam fora da v1.
 */
public enum AjusteFimDeSemana {
  /** Mantém a data. */
  NENHUM,
  /** Volta para a sexta-feira anterior. */
  ANTECIPA,
  /** Vai para a segunda-feira seguinte. */
  POSTERGA
}
