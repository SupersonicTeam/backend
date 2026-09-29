package br.com.agrocontabil.repository;

import java.math.BigDecimal;

/**
 * Total das compras de um insumo no período (issue #142).
 *
 * @param valorTotal      soma do valor dos itens que casaram com o insumo; nulo sem compras
 * @param quantidadeNotas notas distintas em que o insumo aparece
 */
public record CompraInsumoTotal(BigDecimal valorTotal, Long quantidadeNotas) {}
