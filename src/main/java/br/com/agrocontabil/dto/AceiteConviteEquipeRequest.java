package br.com.agrocontabil.dto;

import br.com.agrocontabil.common.Sensivel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Aceite do convite de equipe por quem já tem conta de contador (issue #228).
 *
 * @param token token do fragmento do link
 */
public record AceiteConviteEquipeRequest(@NotBlank @Size(max = 100) @Sensivel String token) {

  @Override
  public String toString() {
    return "AceiteConviteEquipeRequest[token=***]";
  }
}
