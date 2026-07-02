package br.com.agrocontabil.repository;

import java.math.BigDecimal;

/**
 * Projeção de agregação do débito de IBS/CBS das notas de <strong>saída</strong> autorizadas de
 * um produtor em um período (issue Agrocontabil#32 — UC004). Preenchida pela query
 * {@link NotaFiscalRepository#somarDebitosPorPeriodo}.
 *
 * <p>{@code debitoIbs} e {@code debitoCbs} são nulos quando não há notas no período (comportamento
 * do {@code SUM} sobre conjunto vazio) — o serviço trata o nulo como {@code 0.00}.
 * {@code quantidadeNotas} vem de {@code COUNT} e nunca é nulo.
 *
 * @param debitoIbs       somatório do IBS destacado nas saídas autorizadas (nulo se vazio)
 * @param debitoCbs       somatório da CBS destacada nas saídas autorizadas (nulo se vazio)
 * @param quantidadeNotas quantidade de notas de saída autorizadas no período
 */
public record DebitoSaldoAgregado(
    BigDecimal debitoIbs,
    BigDecimal debitoCbs,
    long quantidadeNotas) {}
