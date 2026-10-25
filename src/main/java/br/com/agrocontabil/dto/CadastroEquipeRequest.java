package br.com.agrocontabil.dto;

import br.com.agrocontabil.common.Sensivel;
import br.com.agrocontabil.validation.ValidCpfCnpj;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Cadastro de contador pelo link do convite de equipe (issue #228). O e-mail é o do convite: não
 * vem no request.
 *
 * @param token        token do fragmento do link
 * @param nomeCompleto nome
 * @param cpfCnpj      CPF ou CNPJ, só dígitos
 * @param senha        senha (mesma política do cadastro)
 */
public record CadastroEquipeRequest(
    @NotBlank @Size(max = 100) @Sensivel String token,
    @NotBlank @Size(max = 255) String nomeCompleto,
    @NotBlank
    @Pattern(regexp = "\\d{11}|\\d{14}", message = "Informe o CPF (11) ou CNPJ (14) so com digitos")
    @ValidCpfCnpj
    String cpfCnpj,
    @NotNull @Sensivel
    @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY)
    String senha) {

  @Override
  public String toString() {
    return "CadastroEquipeRequest[***]";
  }
}
