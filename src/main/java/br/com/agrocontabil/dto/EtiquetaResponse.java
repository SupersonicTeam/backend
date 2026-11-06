package br.com.agrocontabil.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * Etiqueta do catálogo do escritório (issue #177).
 *
 * @param id   etiqueta
 * @param nome nome como foi digitado na criação
 */
@Schema(description = "Etiqueta do catálogo do escritório")
public record EtiquetaResponse(
    @Schema(description = "Id da etiqueta", example = "3f2b8c1e-5d4a-4b7e-9a1c-2e8f0d6b7a10")
    UUID id,
    @Schema(description = "Nome da etiqueta, como foi digitado na criação", example = "Soja")
    String nome) {
}
