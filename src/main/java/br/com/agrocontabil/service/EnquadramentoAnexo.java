package br.com.agrocontabil.service;

/**
 * Item de anexo da LC 214/2025 em que um NCM se enquadra, como publicado na tabela oficial
 * (issue #272).
 *
 * @param cClassTrib classificação tributária da hipótese (ex.: {@code 200014})
 * @param anexo número do anexo da LC 214/2025
 * @param descricaoItem texto do item do anexo
 * @param condicao condição de aplicação; pode ser nula
 * @param excecao exceção do item; pode ser nula
 */
public record EnquadramentoAnexo(
    String cClassTrib, int anexo, String descricaoItem, String condicao, String excecao) {
}
