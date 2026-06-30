package br.com.agrocontabil.model;

/**
 * Tipo de operação fiscal codificado por um CFOP (issue #20, RN008).
 *
 * <p>O CFOP (Código Fiscal de Operações e Prestações) classifica a operação como entrada ou
 * saída de mercadoria/serviço sob a ótica do estabelecimento.
 */
public enum TipoOperacaoCfop {
  /** Operação de entrada (CFOP iniciado em 1, 2 ou 3). */
  ENTRADA,
  /** Operação de saída (CFOP iniciado em 5, 6 ou 7). */
  SAIDA
}
