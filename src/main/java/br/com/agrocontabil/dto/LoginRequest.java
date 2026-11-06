package br.com.agrocontabil.dto;

import br.com.agrocontabil.common.Sensivel;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Login por e-mail e senha (issue #206).
 *
 * <p>Sem {@code @Email} de propósito: um e-mail malformado recebe o mesmo 401 de qualquer
 * credencial errada, em vez de um 400 que diferenciaria a resposta.
 *
 * @param email e-mail de login
 * @param senha senha
 */
@Schema(description = "Login por e-mail e senha")
public record LoginRequest(
    @NotBlank
    @Size(max = 254)
    @Schema(example = "produtor@exemplo.com.br")
    String email,

    @NotNull
    @Sensivel
    @Size(max = 1024)
    @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY)
    String senha) {

  /** Sem e-mail nem senha (LGPD e segredo). */
  @Override
  public String toString() {
    return "LoginRequest[]";
  }
}
