package br.com.agrocontabil.repository;

import java.math.BigDecimal;

/**
 * Projeção de agregação do saldo de crédito tributário de um produtor em um período
 * (issue Agrocontabil#30 — RN003). Preenchida pela query de agregação em
 * {@link NotaFiscalRepository#somarCreditosPorPeriodo}.
 *
 * <p>{@code creditoIbs} e {@code creditoCbs} são nulos quando não há notas no período
 * (comportamento do {@code SUM} sobre conjunto vazio) — o serviço trata o nulo como
 * {@code 0.00}. {@code quantidadeNotas} vem de {@code COUNT} e nunca é nulo.
 *
 * @param creditoIbs      somatório do crédito de IBS aproveitável no período (nulo se vazio)
 * @param creditoCbs      somatório do crédito de CBS aproveitável no período (nulo se vazio)
 * @param quantidadeNotas quantidade de notas de entrada consideradas no período
 */
public record CreditoSaldoAgregado(
    BigDecimal creditoIbs,
    BigDecimal creditoCbs,
    long quantidadeNotas) {}
