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
   * <strong>Estado transitório</strong>: a transmissão à SEFAZ foi iniciada e o desfecho ainda
   * não é conhecido (issue #96).
   *
   * <p>Existe para que a chamada de rede à SEFAZ aconteça <em>fora</em> de transação sem abrir
   * janela para transmissão dupla: a nota é marcada como {@code ENVIANDO} sob lock pessimista,
   * numa transação curta que commita antes da rede. Uma segunda requisição concorrente espera o
   * lock, encontra este status e recebe 409 — o XML chega à SEFAZ uma única vez.
   *
   * <p>Nenhuma operação é permitida neste estado (edição, exclusão, geração de XML, envio,
   * cancelamento ou consulta respondem 409). Ele dura o tempo da transmissão.
   *
   * <p>Se o processo cair entre a marcação e o desfecho, a nota fica presa aqui; o
   * {@link br.com.agrocontabil.sefaz.SefazStatusSyncJob} a move para {@link #PENDENTE_SEFAZ}
   * após {@code app.nfe.sefaz.transmissao.timeout-ms}, e a consulta pela chave estabelece a
   * verdade junto ao fisco.
   */
  ENVIANDO,

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
   * <strong>Estado transitório</strong>: o evento de cancelamento foi enviado à SEFAZ e o
   * desfecho ainda não é conhecido (issue #96). Simétrico a {@link #ENVIANDO}.
   *
   * <p>Uma segunda requisição de cancelamento concorrente espera o lock, encontra este status e
   * recebe 409 — o evento é transmitido uma única vez e o {@code NotaCanceladaEvent} é publicado
   * uma única vez (do contrário a apuração seria recalculada em duplicidade).
   *
   * <p>Numa falha de comunicação, a nota volta para {@link #AUTORIZADA} — e <strong>não</strong>
   * para {@link #PENDENTE_SEFAZ}: a consulta de status não sabe reconhecer cancelamento
   * ({@code mapStatus} não mapeia {@link #CANCELADA}), então o job a reautorizaria, a regressão
   * fechada pela issue #79. Repetir o cancelamento é seguro: o fisco responde cStat de evento já
   * vinculado (135/155).
   */
  CANCELANDO,

  /**
   * Nota cancelada junto à SEFAZ (issue #21, RN006). <strong>Estado terminal</strong>:
   * nenhuma operação (edição, exclusão, geração de XML, envio ou novo cancelamento)
   * é permitida após o cancelamento.
   *
   * <p>Só uma nota {@link #AUTORIZADA} pode ser cancelada, e apenas dentro do prazo
   * de 30 dias corridos da emissão (RN006). Ao cancelar, os campos
   * {@code dataCancelamento}, {@code motivoCancelamento} e {@code protocoloCancelamento}
   * ficam preenchidos na entidade. Adicionado o fluxo real na Sprint 3 (issue #21).
   */
  CANCELADA
}
