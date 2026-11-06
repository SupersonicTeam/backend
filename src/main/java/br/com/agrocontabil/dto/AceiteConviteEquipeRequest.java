package br.com.agrocontabil.dto;

import br.com.agrocontabil.common.Sensivel;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Aceite do convite de equipe por quem já tem conta de contador (issue #228).
 *
 * @param token token do fragmento do link
 */
@Schema(description = "Aceite do convite de equipe pelo contador que já tem conta")
public record AceiteConviteEquipeRequest(
    @Schema(description = "Token do convite, vindo do fragmento do link")
    @NotBlank @Size(max = 100) @Sensivel String token) {

  @Override
  public String toString() {
    return "AceiteConviteEquipeRequest[token=***]";
  }
}
