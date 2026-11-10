package br.com.agrocontabil.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * DTO de entrada para o cancelamento de uma NF-e (issue #21 — RN006).
 *
 * <p>A justificativa segue o padrão da SEFAZ para eventos de cancelamento: de 15 a 255
 * caracteres. A validação é declarativa (Bean Validation), aplicada pelo {@code @Valid}
 * no controller.
 *
 * @param motivo justificativa do cancelamento (15 a 255 caracteres)
 */
@Schema(description = "Dados para cancelamento de uma NF-e autorizada")
public record CancelamentoRequest(

    @Schema(
        description = "Justificativa do cancelamento (15 a 255 caracteres, padrao SEFAZ)",
        example = "Cancelamento por erro na quantidade dos produtos vendidos",
        minLength = 15,
        maxLength = 255)
    @NotBlank(message = "O motivo do cancelamento é obrigatório.")
    @Size(min = 15, max = 255,
        message = "O motivo do cancelamento deve ter entre 15 e 255 caracteres.")
    String motivo) {}
