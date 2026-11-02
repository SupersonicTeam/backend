package br.com.agrocontabil.repository;

/**
 * Item de nota lido para os sinais de nota a conferir (issue #276): só o que a regra usa, sem
 * carregar a nota, o XML nem o produtor.
 *
 * @param numeroItem número do item
 * @param descricao  descrição digitada
 * @param cfop       CFOP
 * @param ncm        NCM
 */
public record ItemParaSinal(Integer numeroItem, String descricao, String cfop, String ncm) {}
