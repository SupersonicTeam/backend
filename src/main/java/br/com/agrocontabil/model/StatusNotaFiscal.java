package br.com.agrocontabil.model;

/**
 * Status do ciclo de vida de uma {@link NotaFiscal}.
 *
 * <p>Fluxo principal: {@link #RASCUNHO} → {@link #XML_GERADO} → {@link #AUTORIZADA}.
 * Desvios: {@link #REJEITADA} (fisco rejeitou os dados) e {@link #PENDENTE_SEFAZ}
 * (situação indefinida após consulta). Cancelamento: {@link #CANCELADA}.
 *
 * <p>A coluna {@code status} é {@code VARCHAR(20)} — todos os nomes cabem.
 *
 * <p>Adicionados na Sprint 3 (issue #15): {@link #REJEITADA} e {@link #PENDENTE_SEFAZ}.
 */
public enum StatusNotaFiscal {

  /**
   * Nota em edição — ainda não gerou XML. Estado inicial de toda nota criada pelo sistema.
   */
  RASCUNHO,

  /**
   * XML da NF-e foi gerado e assinado localmente. Pronto para envio à SEFAZ.
   */
  XML_GERADO,

  /**
   * Nota transmitida e autorizada pela SEFAZ (cStat 100).
   * Protocolo de autorização e data de autorização estão preenchidos na entidade.
   */
  AUTORIZADA,

  /**
   * Nota rejeitada pelo fisco por irregularidade nos dados (cStat 2xx/4xx da SEFAZ)
   * ou denegada por irregularidade cadastral do emitente.
   *
   * <p>Não é exceção: é resultado válido persistido com cStat e motivo.
   * A nota deve ser corrigida e um novo XML gerado antes de reenviar.
   * Adicionado na Sprint 3 (issue #15 — integração SEFAZ sandbox).
   */
  REJEITADA,

  /**
   * Envio enviado, porém a situação junto à SEFAZ ainda não foi confirmada.
   *
   * <p>Ocorre quando a consulta de status retorna situação indefinida ou timeout.
   * O sistema deve repetir a consulta de status (issue #16 — Sprint 3).
   * Adicionado na Sprint 3 (issue #15 — integração SEFAZ sandbox).
   */
  PENDENTE_SEFAZ,

  /**
   * Nota cancelada junto à SEFAZ. Reservado para integração futura (Sprint 7+).
   */
  CANCELADA
}
