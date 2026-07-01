package br.com.agrocontabil.model;

/**
 * Discriminador do tipo de documento fiscal de uma {@link NotaFiscal} (issue #18).
 *
 * <p>Ambos os tipos sao emitidos no layout NF-e modelo 55; a distincao serve ao fluxo de
 * emissao e a informacoes complementares. A NFA-e real possui indicadores fiscais especificos
 * (debito tecnico documentado — ficam para refinamento futuro).
 */
public enum TipoDocumentoFiscal {

  /** Nota Fiscal eletronica padrao (produtor pessoa fisica ou juridica). */
  NFE,

  /**
   * Nota Fiscal Avulsa do produtor rural (issue #18). Emitida por produtor pessoa fisica,
   * com CPF no emitente.
   */
  NFA
}
