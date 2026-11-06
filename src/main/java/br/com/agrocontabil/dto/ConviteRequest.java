package br.com.agrocontabil.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Convite do escritório para um produtor (issue #177): só e-mail, nunca CPF/CNPJ.
 *
 * @param email e-mail do produtor (com ou sem conta)
 */
@Schema(description = "Convite do escritório para um produtor, só por e-mail")
public record ConviteRequest(
    @Schema(description = "E-mail do produtor, com ou sem conta", example = "produtor@exemplo.com.br")
    @NotBlank @Email @Size(max = 254) String email) {

  @Override
  public String toString() {
    return "ConviteRequest[email=***]";
  }
}
