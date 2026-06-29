package br.com.agrocontabil.model;

/**
 * InvoiceOrigin da invoice fiscal no sistema.
 *
 * <ul>
 *   <li>{@link #PROPRIA} — emitida pelo producer autenticado (fluxo padrão).</li>
 *   <li>{@link #IMPORTADA} — XML de terceiro importado (invoice de entrada); o producer
 *       autenticado é o owner/destinatário e o emitente é um terceiro.</li>
 * </ul>
 */
public enum InvoiceOrigin {
  PROPRIA,
  IMPORTADA
}
