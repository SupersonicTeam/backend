package br.com.agrocontabil.dto;

import br.com.agrocontabil.security.PapelUsuario;
import br.com.agrocontabil.validation.ValidCpfCnpj;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Pedido de cadastro de um produtor ou contador com credencial de acesso (issue #205).
 *
 * <p>A senha só é checada aqui quanto à presença. As regras de força ficam na
 * {@code PoliticaSenha}, que responde sem ecoar o valor; uma anotação {@code @Size} aqui faria o
 * erro de validação devolver a senha no {@code rejectedValue}.
 *
 * @param papel       PRODUTOR ou CONTADOR
 * @param nomeCompleto nome do produtor ou do escritório
 * @param cpfCnpj     CPF (11) ou CNPJ (14), só dígitos, com dígito verificador válido — obrigatório
 * @param email       e-mail de login
 * @param senha       senha escolhida
 */
public record CadastroRequest(
    @NotNull
    @Schema(description = "Papel da conta", example = "PRODUTOR")
    PapelUsuario papel,

    @NotBlank
    @Size(max = 255)
    @Schema(example = "Fazenda Boa Vista")
    String nomeCompleto,

    @NotBlank
    @Pattern(regexp = "\\d{11}|\\d{14}", message = "Informe o CPF (11) ou CNPJ (14) so com digitos")
    @ValidCpfCnpj
    @Schema(description = "CPF ou CNPJ, so digitos", example = "52998224725")
    String cpfCnpj,

    @NotBlank
    @Email
    @Size(max = 254)
    @Schema(example = "produtor@exemplo.com.br")
    String email,

    @NotNull
    @Schema(description = "Minimo de 10 caracteres; no maximo 72 bytes", format = "password",
        accessMode = Schema.AccessMode.WRITE_ONLY)
    String senha) {

  /** Sem a senha: o {@code toString} padrão do record a poria em qualquer log do request. */
  @Override
  public String toString() {
    return "CadastroRequest[papel=" + papel + ", email=" + email + "]";
  }
}
