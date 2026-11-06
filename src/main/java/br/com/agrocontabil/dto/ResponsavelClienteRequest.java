package br.com.agrocontabil.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * Troca do responsável pelo cliente (issue #228).
 *
 * @param responsavelId membro do escritório; nulo deixa o cliente sem responsável
 */
@Schema(description = "Troca do responsável pelo cliente")
public record ResponsavelClienteRequest(
    @Schema(description = "Membro do escritório; nulo deixa o cliente sem responsável", example = "3f2b8c1e-5d4a-4b7e-9a1c-2e8f0d6b7a10")
    UUID responsavelId) {
}
