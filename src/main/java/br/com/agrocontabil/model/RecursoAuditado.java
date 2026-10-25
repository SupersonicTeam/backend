package br.com.agrocontabil.model;

/** Tipo do recurso sobre o qual uma operação auditada agiu (issue #149). */
public enum RecursoAuditado {
  NOTA_FISCAL,
  GUIA_FISCAL,
  APURACAO,
  CONTA,
  /**
   * Vinculo da carteira (issue #177). Na acao do escritorio o recurso_id e o produtor (o
   * escritorio nomeia o cliente, nao o vinculo); na do produtor, o vinculo encerrado. O produtor
   * afetado vai sempre em produtor_id.
   */
  VINCULO_CARTEIRA,
  CONVITE_CARTEIRA
}
