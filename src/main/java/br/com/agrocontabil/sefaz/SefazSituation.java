package br.com.agrocontabil.sefaz;

/**
 * Situação retornada pela SEFAZ após o envio de uma NF-e.
 *
 * <p>Mapeada a partir do field {@code cStat} da resposta do webservice NF-e (layout 4.00).
 * O {@link MockSefazTransport} retorna {@link #AUTORIZADA} de forma determinística.
 */
public enum SefazSituation {

  /**
   * NF-e autorizada pelo fisco (cStat 100).
   * Transiciona a invoice para {@code AUTORIZADA}.
   */
  AUTORIZADA,

  /**
   * NF-e rejeitada pelo fisco por irregularidade nos dados (cStat 2xx/4xx da SEFAZ).
   * Transiciona a invoice para {@code REJEITADA} — não é exceção, é result válido persistido.
   */
  REJEITADA,

  /**
   * Consulta de status retornou situação pendente/indefinida (ex.: timeout na SEFAZ).
   * Transiciona a invoice para {@code PENDENTE_SEFAZ}.
   */
  PENDENTE,

  /**
   * NF-e denegada pelo fisco (emitente com irregularidade cadastral).
   * Também mapeia para {@code REJEITADA} no domínio da aplicação.
   */
  DENEGADA
}
