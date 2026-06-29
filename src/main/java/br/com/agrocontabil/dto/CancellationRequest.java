package br.com.agrocontabil.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * DTO de entrada para o cancelamento de uma NF-e.
 *
 * <p>O reason (justificativa) é obrigatório e deve ter 15–255 caracteres, espelhando
 * a regra da SEFAZ para o field {@code xJust} do evento de cancelamento.
 */
@Schema(description = "Dados para cancelamento de uma nota fiscal")
public record CancellationRequest(

    @Schema(description = "Justificativa do cancelamento (15 a 255 caracteres)",
        example = "Erro na emissao: dados do destinatario incorretos")
    @NotBlank(message = "motivo e obrigatorio")
    @Size(min = 15, max = 255, message = "motivo deve ter entre 15 e 255 caracteres")
    @JsonProperty("motivo") String reason
) {}
