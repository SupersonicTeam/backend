package br.com.agrocontabil.model;

/**
 * Âmbito territorial de uma operação codificado pelo 1.º dígito do CFOP (issue #20, RN008).
 *
 * <p>Para operações de saída: {@code 5} → interno, {@code 6} → interestadual,
 * {@code 7} → exterior.
 */
public enum AmbitoCfop {
  /** Operação dentro da mesma UF do emitente (CFOP 5xxx). */
  INTERNO,
  /** Operação entre UFs distintas (CFOP 6xxx). */
  INTERESTADUAL,
  /** Operação com o exterior (CFOP 7xxx). */
  EXTERIOR
}
