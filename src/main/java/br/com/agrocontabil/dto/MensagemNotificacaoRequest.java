package br.com.agrocontabil.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Mensagem livre ao produtor por WhatsApp (issue #182).
 *
 * @param mensagem texto, 1 a 500 caracteres
 */
@Schema(description = "Mensagem livre enviada ao produtor por WhatsApp")
public record MensagemNotificacaoRequest(
    @Schema(description = "Texto da mensagem (1 a 500 caracteres)", example = "Favor enviar o extrato até sexta-feira")
    @NotBlank @Size(max = 500) String mensagem) {
}
