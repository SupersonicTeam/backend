package br.com.agrocontabil.dto;

import br.com.agrocontabil.model.FuncaoEscritorio;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Convite para a equipe (issue #228).
 *
 * @param email  e-mail do convidado (com ou sem conta)
 * @param funcao função que ele terá
 */
public record ConviteMembroRequest(
    @NotBlank @Email @Size(max = 254) String email,
    @NotNull FuncaoEscritorio funcao) {

  @Override
  public String toString() {
    return "ConviteMembroRequest[funcao=" + funcao + "]";
  }
}
