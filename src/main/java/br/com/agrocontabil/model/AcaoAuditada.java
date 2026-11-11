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
  VINCULO_ENCERRADO,
  // Equipe do escritorio (issue #228)
  MEMBRO_CONVIDADO,
  MEMBRO_ENTROU,
  MEMBRO_REMOVIDO,
  FUNCAO_ALTERADA,
  RESPONSAVEL_ALTERADO,
  ESCRITORIO_ALTERADO,
  // Endereco proprio do escritorio (issue #464): definir, trocar ou remover o slug
  ESCRITORIO_ENDERECO_ALTERADO,
  // Conta pessoal que vira empresarial (issue #519)
  ESCRITORIO_TORNADO_EMPRESARIAL,
  // Revisao da nota pelo contador (issue #178)
  NOTA_ENVIADA_REVISAO,
  NOTA_RETIRADA_REVISAO,
  NOTA_APROVADA,
  NOTA_DEVOLVIDA,
  // Pendencias e documentos (issue #180)
  PENDENCIA_CRIADA,
  PENDENCIA_ANEXO_ENVIADO,
  PENDENCIA_CONCLUIDA,
  PENDENCIA_REJEITADA,
  PENDENCIA_CANCELADA,
  PENDENCIA_REABERTA,
  // Antivirus dos anexos (issue #227): ameaca achada no envio ou pelo job
  PENDENCIA_ANEXO_INFECTADO,
  // Notificacao por WhatsApp (issue #182): disparo do produtor ou do escritorio
  NOTIFICACAO_REGISTRADA,
  // Modelos de pedido de documento do escritorio (issue #517): o catalogo, nunca o texto
  MODELO_PEDIDO_CRIADO,
  MODELO_PEDIDO_ALTERADO,
  MODELO_PEDIDO_APAGADO
}
