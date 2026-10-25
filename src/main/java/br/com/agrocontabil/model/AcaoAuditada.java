package br.com.agrocontabil.model;

/**
 * Operações críticas registradas na auditoria (issue #149, RNF012).
 *
 * <p>Cada uma corresponde a um método de service anotado com {@code @Auditado}. Os nomes vão ao
 * banco como texto: renomear um valor exige changeSet que atualize as linhas antigas.
 */
public enum AcaoAuditada {
  NOTA_CRIADA,
  NOTA_ATUALIZADA,
  NOTA_EXCLUIDA,
  NOTA_XML_GERADO,
  NOTA_ENVIADA_SEFAZ,
  NOTA_CANCELADA,
  NOTAS_IMPORTADAS,
  GUIA_CRIADA,
  GUIA_PAGA,
  APURACAO_REABERTA,
  SENHA_ALTERADA,
  SESSOES_ENCERRADAS,
  DADOS_PESSOAIS_EXCLUIDOS,
  // Carteira do escritorio (issue #177)
  CONVITE_ENVIADO,
  CONVITE_ACEITO,
  CONVITE_RECUSADO,
  VINCULO_SUSPENSO,
  VINCULO_REATIVADO,
  VINCULO_ENCERRADO
}
