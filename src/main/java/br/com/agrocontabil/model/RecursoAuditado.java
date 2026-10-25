package br.com.agrocontabil.model;

/** Tipo do recurso sobre o qual uma operação auditada agiu (issue #149). */
public enum RecursoAuditado {
  NOTA_FISCAL,
  GUIA_FISCAL,
  APURACAO,
  CONTA,
  /** Vinculo da carteira (issue #177): o recurso_id e o do produtor. */
  VINCULO_CARTEIRA,
  CONVITE_CARTEIRA
}
