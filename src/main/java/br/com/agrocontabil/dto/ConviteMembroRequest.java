package br.com.agrocontabil.dto;

import br.com.agrocontabil.model.FuncaoEscritorio;
import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "Convite para entrar na equipe do escritório")
public record ConviteMembroRequest(
    @Schema(description = "E-mail do convidado, com ou sem conta", example = "colega@exemplo.com.br")
    @NotBlank @Email @Size(max = 254) String email,
    @Schema(description = "Função que o convidado terá", example = "CONTADOR")
    @NotNull FuncaoEscritorio funcao) {

  @Override
  public String toString() {
    return "ConviteMembroRequest[funcao=" + funcao + "]";
  }
}
