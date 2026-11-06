package br.com.agrocontabil.repository;

import java.math.BigDecimal;

/**
 * Item de nota lido para os sinais de nota a conferir (issue #276): só o que as regras usam, sem
 * carregar a nota, o XML nem o produtor.
 *
 * @param numeroItem    número do item
 * @param descricao     descrição digitada
 * @param cfop          CFOP
 * @param ncm           NCM
 * @param unidade       unidade comercial
 * @param quantidade    quantidade
 * @param valorUnitario valor unitário
 * @param cstIcms        CST do ICMS informado (nulo = não informado)
 * @param ufEmitente     UF do emitente (regra do CST padrão do ICMS)
 * @param ufDestinatario UF do destinatário
 * @param ieDestinatario IE do destinatário (só se há ou não; nunca sai do service)
 */
public record ItemParaSinal(Integer numeroItem, String descricao, String cfop, String ncm,
    String unidade, BigDecimal quantidade, BigDecimal valorUnitario, String cstIcms,
    String ufEmitente, String ufDestinatario, String ieDestinatario) {}
