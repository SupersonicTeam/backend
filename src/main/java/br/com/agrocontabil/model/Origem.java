package br.com.agrocontabil.model;

/**
 * Origem da nota fiscal no sistema.
 *
 * <ul>
 *   <li>{@link #PROPRIA} — emitida pelo produtor autenticado (fluxo padrão).</li>
 *   <li>{@link #IMPORTADA} — XML de terceiro importado (nota de entrada); o produtor
 *       autenticado é o dono/destinatário e o emitente é um terceiro.</li>
 * </ul>
 */
public enum Origem {
  PROPRIA,
  IMPORTADA
}
