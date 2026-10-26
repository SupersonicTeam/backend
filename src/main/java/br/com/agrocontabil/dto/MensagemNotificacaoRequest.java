package br.com.agrocontabil.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Mensagem livre ao produtor por WhatsApp (issue #182).
 *
 * @param mensagem texto, 1 a 500 caracteres
 */
public record MensagemNotificacaoRequest(
    @NotBlank @Size(max = 500) String mensagem) {
}
