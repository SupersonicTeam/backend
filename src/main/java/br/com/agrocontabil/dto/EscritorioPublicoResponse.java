package br.com.agrocontabil.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * O que o endereço próprio de um escritório mostra a quem ainda não entrou (issue #464): só o
 * nome, para a tela de login com a marca do escritório. Nunca id, CNPJ, membros ou contato.
 *
 * @param slug      o slug pedido
 * @param nome      nome do escritório
 * @param slugAtual quando o slug pedido está em carência, o endereço atual do escritório (o
 *                  frontend redireciona); nulo no endereço ativo
 */
@Schema(description = "Escritório público de um endereço próprio (tela de login)")
public record EscritorioPublicoResponse(
    @Schema(description = "Slug pedido", example = "ruraloeste")
    String slug,
    @Schema(description = "Nome do escritório", example = "Contabilidade Rural Oeste")
    String nome,
    @Schema(description = "Endereço atual, quando o pedido está em carência (redirecionar); "
        + "nulo no endereço ativo", nullable = true)
    String slugAtual) {
}
