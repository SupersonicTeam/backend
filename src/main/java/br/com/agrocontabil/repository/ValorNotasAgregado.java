package br.com.agrocontabil.repository;

import java.math.BigDecimal;

/**
 * Projeção de agregação do valor total (R$) das notas autorizadas de um produtor em um período,
 * por origem (issue Agrocontabil#71 — KPIs financeiros do dashboard). Preenchida pela query
 * {@link NotaFiscalRepository#somarValorNotasPorOrigem}.
 *
 * <p>{@code valorPropria} e {@code valorImportada} só são nulos quando <strong>não há nenhuma
 * nota autorizada do produtor no período</strong> (comportamento do {@code SUM} sobre conjunto
 * vazio) — o serviço trata esse nulo como {@code 0.00}. Quando há notas no período mas de outra
 * origem, o {@code CASE WHEN ... ELSE 0} da query já resulta em {@code 0.00}, não nulo. A
 * tradução para fluxo de caixa (entradas/saídas em R$) é feita na camada de serviço, não aqui:
 * este repositório permanece neutro quanto à semântica financeira.
 *
 * @param valorPropria   somatório do valor total das notas próprias (saída de mercadoria/venda)
 *                       autorizadas no período (nulo apenas se não houver nenhuma nota autorizada
 *                       no período)
 * @param valorImportada somatório do valor total das notas importadas (entrada de
 *                       mercadoria/compra) autorizadas no período (nulo apenas se não houver
 *                       nenhuma nota autorizada no período)
 */
public record ValorNotasAgregado(
    BigDecimal valorPropria,
    BigDecimal valorImportada) {}
