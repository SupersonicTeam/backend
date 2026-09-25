package br.com.agrocontabil.sefaz;

/**
 * Situação retornada pela SEFAZ após o envio de uma NF-e.
 *
 * <p>Mapeada a partir do campo {@code cStat} da resposta do webservice NF-e (layout 4.00).
 * O {@link MockSefazTransport} retorna {@link #AUTORIZADA} de forma determinística.
 */
public enum SituacaoSefaz {

  /**
   * NF-e autorizada pelo fisco (cStat 100).
   * Transiciona a nota para {@code AUTORIZADA}.
   */
  AUTORIZADA,

  /**
   * NF-e rejeitada pelo fisco por irregularidade nos dados (cStat 2xx/4xx da SEFAZ).
   * Transiciona a nota para {@code REJEITADA} — não é exceção, é resultado válido persistido.
   */
  REJEITADA,

  /**
   * Consulta de status retornou situação pendente/indefinida (ex.: timeout na SEFAZ).
   * Transiciona a nota para {@code PENDENTE_SEFAZ}.
   */
  PENDENTE,

  /**
   * NF-e denegada pelo fisco (emitente com irregularidade cadastral).
   * Também mapeia para {@code REJEITADA} no domínio da aplicação.
   */
  DENEGADA,

  /**
   * <strong>Só na consulta.</strong> A chave não consta na base da SEFAZ (cStat 217): o fisco
   * nunca recebeu esta NF-e (issue #83).
   *
   * <p>Na sincronização, uma nota em {@code PENDENTE_SEFAZ} volta para {@code XML_GERADO} e pode
   * ser reenviada. Na reconciliação imediata após um timeout, porém, não se conclui nada: a SEFAZ
   * pode ainda estar processando o envio.
   */
  NAO_ENCONTRADA,

  /**
   * <strong>Só na consulta.</strong> A NF-e foi autorizada e depois cancelada no fisco
   * (cStat 101/151, cancelamento homologado) (issue #83).
   *
   * <p>Permite reconhecer um cancelamento cuja resposta se perdeu, em vez de tratar a nota como
   * autorizada.
   */
  CANCELADA
}
