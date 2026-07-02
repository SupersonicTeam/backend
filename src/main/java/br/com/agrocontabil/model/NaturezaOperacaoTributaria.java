package br.com.agrocontabil.model;

/**
 * Natureza tributaria de uma operacao para fins de apuracao de IBS/CBS
 * (Sprint 4 — issue Agrocontabil#30, RF015).
 *
 * <p>Classificada pelo 1.º digito do CFOP do item, refletindo a otica do estabelecimento:
 * <ul>
 *   <li><strong>DEBITO</strong> — operacao de <em>saida</em> (CFOP iniciado em 5, 6 ou 7):
 *       gera imposto a recolher (debito de IBS/CBS).</li>
 *   <li><strong>CREDITO</strong> — operacao de <em>entrada</em> (CFOP iniciado em 1, 2 ou 3):
 *       gera direito a credito pela nao-cumulatividade (EC 132/2023, art. 156-A, § 1.º, VIII;
 *       LC 214/2025, art. 47).</li>
 * </ul>
 *
 * <p>A natureza e persistida por item e consumida pela apuracao mensal (issue #41), que soma
 * debitos e credits do periodo para chegar ao saldo devido.
 */
public enum NaturezaOperacaoTributaria {
  /** Operacao de saida (CFOP 5/6/7) — gera debito de IBS/CBS a recolher. */
  DEBITO,
  /** Operacao de entrada (CFOP 1/2/3) — gera credito de IBS/CBS a aproveitar. */
  CREDITO
}
