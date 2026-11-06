package br.com.agrocontabil.dto;

import br.com.agrocontabil.common.Sensivel;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Confirmação da exclusão dos dados pessoais (issue #148): a senha atual. Operação irreversível —
 * um access token roubado sozinho não a dispara.
 *
 * @param senha senha atual
 */
@Schema(description = "Confirmação da exclusão dos dados pessoais com a senha atual; operação irreversível")
public record ExclusaoDadosRequest(
    @NotNull @Size(max = 1024) @Sensivel
    @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY) String senha) {

  /** Sem a senha. */
  @Override
  public String toString() {
    return "ExclusaoDadosRequest[]";
  }
}
