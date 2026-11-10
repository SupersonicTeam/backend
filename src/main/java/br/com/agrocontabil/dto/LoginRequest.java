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
 * <p>{@code escritorio} (#464) é o slug do endereço do escritório, mandado pela tela de login de
 * {@code {slug}.agroconia.com.br}. Sem validação nenhuma, nem de tamanho: um valor estranho recebe o
 * mesmo 401, nunca um 400 que diferencie a resposta (contrato da #464). Ausente ou em branco no
 * host principal, o login é o de sempre.
 *
 * @param email      e-mail de login
 * @param senha      senha
 * @param escritorio slug do endereço do escritório (opcional)
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
    String senha,

    @Schema(description = "Slug do endereco do escritorio, so na tela de login do subdominio "
        + "(opcional)", example = "ruraloeste", nullable = true)
    String escritorio) {

  /**
   * Login sem escritório (o de sempre).
   *
   * @param email e-mail de login
   * @param senha senha
   */
  public LoginRequest(String email, String senha) {
    this(email, senha, null);
  }

  /** Sem e-mail nem senha (LGPD e segredo). */
  @Override
  public String toString() {
    return "LoginRequest[]";
  }
}
