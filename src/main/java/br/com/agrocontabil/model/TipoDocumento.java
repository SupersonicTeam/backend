package br.com.agrocontabil.model;

/**
 * Tipo de documento fiscal emitido por um {@link Produtor}.
 *
 * <ul>
 *   <li>{@link #NFE} — Nota Fiscal eletronica modelo 55 (fluxo padrao).</li>
 *   <li>{@link #NFA} — Nota Fiscal Avulsa eletronica do produtor rural pessoa fisica.
 *       Reusa o layout NF-e 4.00 modelo 55; o emitente e PF (CPF).</li>
 * </ul>
 */
public enum TipoDocumento {
  NFE,
  NFA
}
