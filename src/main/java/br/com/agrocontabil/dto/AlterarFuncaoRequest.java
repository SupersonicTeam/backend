package br.com.agrocontabil.dto;

import br.com.agrocontabil.model.FuncaoEscritorio;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * Troca da função de um membro (issue #228).
 *
 * @param funcao função nova
 */
@Schema(description = "Troca da função de um membro da equipe do escritório")
public record AlterarFuncaoRequest(
    @Schema(description = "Nova função no escritório", example = "CONTADOR")
    @NotNull FuncaoEscritorio funcao) {
}
