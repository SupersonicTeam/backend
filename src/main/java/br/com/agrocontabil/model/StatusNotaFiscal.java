package br.com.agrocontabil.model;

/**
 * Status do ciclo de vida de uma {@link NotaFiscal}.
 *
 * <p>Na Sprint 2, apenas {@link #RASCUNHO} e {@link #XML_GERADO} são utilizados pelo fluxo
 * implementado. Os demais valores são reservados para a integração com a SEFAZ (transmissão,
 * autorização e cancelamento), prevista em sprints futuras.
 */
public enum StatusNotaFiscal {

  /**
   * Nota em edição — ainda não gerou XML. Estado inicial de toda nota criada pelo sistema.
   */
  RASCUNHO,

  /**
   * XML da NF-e foi gerado localmente. Reservado para sprints futuras (integração SEFAZ).
   */
  XML_GERADO,

  /**
   * Nota transmitida e autorizada pela SEFAZ. Reservado para integração futura.
   */
  AUTORIZADA,

  /**
   * Nota cancelada junto à SEFAZ. Reservado para integração futura.
   */
  CANCELADA
}
