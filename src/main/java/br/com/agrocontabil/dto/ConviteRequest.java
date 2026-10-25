package br.com.agrocontabil.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Convite do escritório para um produtor (issue #177): só e-mail, nunca CPF/CNPJ.
 *
 * @param email e-mail do produtor (com ou sem conta)
 */
public record ConviteRequest(@NotBlank @Email @Size(max = 254) String email) {

  @Override
  public String toString() {
    return "ConviteRequest[email=***]";
  }
}
