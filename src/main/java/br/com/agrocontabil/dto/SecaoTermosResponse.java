package br.com.agrocontabil.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Uma seção dos termos de uso (#498): título, parágrafos e, quando há, uma lista de itens. Texto
 * puro, sem HTML nem marcação: a tela numera as seções pela ordem e põe o {@code id} como âncora.
 *
 * @param id         identificador estável da seção, para âncora e link ({@code quem-somos})
 * @param titulo     título da seção
 * @param paragrafos parágrafos da seção, na ordem
 * @param itens      itens de lista que vêm depois dos parágrafos; vazia quando a seção não tem
 */
@Schema(description = "Seção dos termos de uso, em texto puro")
public record SecaoTermosResponse(
    @Schema(description = "Identificador estável da seção, para âncora", example = "quem-somos")
    String id,
    @Schema(description = "Título da seção")
    String titulo,
    @Schema(description = "Parágrafos da seção, na ordem")
    List<String> paragrafos,
    @Schema(description = "Itens de lista, depois dos parágrafos; vazia quando não há")
    List<String> itens) {
}
