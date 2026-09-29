package br.com.agrocontabil.repository;

import java.math.BigDecimal;

/**
 * Compras de um insumo agregadas por unidade de medida (issue #142). Somar quantidades de unidades
 * diferentes (KG com L) não teria sentido, por isso a quantidade vem separada por unidade.
 *
 * @param unidade    unidade comercial do item ({@code KG}, {@code L}, {@code UN}...)
 * @param quantidade soma das quantidades dos itens nessa unidade
 * @param valorTotal soma do valor dos itens nessa unidade
 */
public record CompraInsumoAgregado(String unidade, BigDecimal quantidade, BigDecimal valorTotal) {}
