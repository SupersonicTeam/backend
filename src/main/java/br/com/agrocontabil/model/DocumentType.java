package br.com.agrocontabil.model;

/**
 * Tipo de documento fiscal emitido por um {@link Producer}.
 *
 * <ul>
 *   <li>{@link #NFE} — Nota Fiscal eletronica model 55 (fluxo padrao).</li>
 *   <li>{@link #NFA} — Nota Fiscal Avulsa eletronica do producer rural pessoa fisica.
 *       Reusa o layout NF-e 4.00 model 55; o emitente e PF (CPF).</li>
 * </ul>
 */
public enum DocumentType {
  NFE,
  NFA
}
