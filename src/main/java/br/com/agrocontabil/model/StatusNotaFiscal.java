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
 * Adicionado na Sprint 3 (issue #22): {@link #COM_INCONSISTENCIAS}.
 */
public enum StatusNotaFiscal {

  /**
   * Nota em edição — ainda não gerou XML. Estado inicial de toda nota criada pelo sistema.
   */
  RASCUNHO,

  /**
   * A última tentativa de gerar o XML falhou na validação (regras fiscais ou XSD).
   *
   * <p>As inconsistências encontradas ficam persistidas em {@code nota_fiscal_validacao}
   * (issue #22) e podem ser consultadas em {@code GET /api/notas/{id}/validacoes}. A nota
   * permanece <strong>editável</strong> — equivale a um RASCUNHO que precisa de correção:
   * após corrigir os campos, uma nova chamada a {@code gerar-xml} pode promovê-la a
   * {@link #XML_GERADO}. Adicionado na Sprint 3 (issue #22).
   */
  COM_INCONSISTENCIAS,

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
