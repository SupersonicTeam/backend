package br.com.agrocontabil.repository;

import java.math.BigDecimal;

/**
 * Vendas autorizadas de um produtor num período, agrupadas pelo tipo de comprador (RN009, issue
 * #145). Sem o documento do comprador: só a categoria, derivada dele no banco.
 *
 * @param adquirente     {@code PESSOA_JURIDICA}, {@code PESSOA_FISICA} ou {@code EXTERIOR}
 * @param quantidade     notas
 * @param valorProdutos  soma do valor dos produtos (receita bruta da comercialização)
 * @param valorIbs       soma do IBS destacado
 * @param valorCbs       soma da CBS destacada
 */
public record VendaPorAdquirenteAgregado(
    String adquirente,
    long quantidade,
    BigDecimal valorProdutos,
    BigDecimal valorIbs,
    BigDecimal valorCbs) {}
