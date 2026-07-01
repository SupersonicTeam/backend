package br.com.agrocontabil.model;

/**
 * Origem de uma {@link NotaFiscal} (issue #19 — importacao de XML, UC002).
 *
 * <p>Distingue notas emitidas pelo proprio produtor das notas importadas a partir do XML
 * de um terceiro (notas de entrada/compras, em que o produtor autenticado e o
 * <strong>destinatario</strong>). A distincao afeta a unicidade: notas
 * {@link #PROPRIA} respeitam o indice unico parcial {@code (produtor_id, numero, serie,
 * modelo)}; notas {@link #IMPORTADA} sao deduplicadas apenas pela {@code chave_acesso}
 * (o numero/serie sao do emitente terceiro e podem colidir entre fornecedores distintos).
 */
public enum OrigemNotaFiscal {

  /** Nota emitida pelo proprio produtor autenticado (fluxo de emissao padrao). */
  PROPRIA,

  /**
   * Nota importada do XML de um terceiro emitente. O produtor autenticado e o destinatario
   * (nota de entrada/compra). Deduplicada pela chave de acesso.
   */
  IMPORTADA
}
