package br.com.agrocontabil.repository;

import java.math.BigDecimal;

/**
 * Projeção de agregação do valor total (R$) das notas autorizadas de um produtor em um período,
 * por origem (issue Agrocontabil#71 — KPIs financeiros do dashboard). Preenchida pela query
 * {@link NotaFiscalRepository#somarValorNotasPorOrigem}.
 *
 * <p>{@code valorPropria} e {@code valorImportada} são nulos quando não há notas da origem no
 * período (comportamento do {@code SUM} sobre conjunto vazio) — o serviço trata o nulo como
 * {@code 0.00}. A tradução para fluxo de caixa (entradas/saídas em R$) é feita na camada de
 * serviço, não aqui: este repositório permanece neutro quanto à semântica financeira.
 *
 * @param valorPropria   somatório do valor total das notas próprias (saída de mercadoria/venda)
 *                       autorizadas no período (nulo se vazio)
 * @param valorImportada somatório do valor total das notas importadas (entrada de
 *                       mercadoria/compra) autorizadas no período (nulo se vazio)
 */
public record ValorNotasAgregado(
    BigDecimal valorPropria,
    BigDecimal valorImportada) {}
