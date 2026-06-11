package br.com.agrocontabil.model;

/** Âmbito territorial da operação fiscal de um CFOP. */
public enum AmbitoOperacao {
  /** Operação dentro da mesma UF (CFOP 1xxx/5xxx). */
  INTERNA,
  /** Operação entre UFs diferentes (CFOP 2xxx/6xxx). */
  INTERESTADUAL
}
