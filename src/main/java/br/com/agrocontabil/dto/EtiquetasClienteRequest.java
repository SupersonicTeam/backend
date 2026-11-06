package br.com.agrocontabil.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Etiquetas de um cliente, substituindo as anteriores (issue #177). Nome que ainda não existe no
 * catálogo do escritório entra nele.
 *
 * @param etiquetas nomes (até 10, cada um com 1 a 30 caracteres); lista vazia tira todas
 */
@Schema(description = "Etiquetas de um cliente, substituindo as anteriores")
public record EtiquetasClienteRequest(
    @Schema(description = "Nomes das etiquetas (até 10, de 1 a 30 caracteres); lista vazia remove todas", example = "[\"Soja\", \"Leite\"]")
    @NotNull @Size(max = 10) List<@NotBlank @Size(max = 30) String> etiquetas) {
}
