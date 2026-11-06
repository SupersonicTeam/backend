package br.com.agrocontabil.dto;

import br.com.agrocontabil.common.Sensivel;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * Edição da ficha do cliente (issue #177). Observação vazia ou nula apaga a anterior.
 *
 * @param observacoes observações internas do escritório; {@link Sensivel} para o erro de tamanho
 *                    nunca ecoar o texto
 */
@Schema(description = "Edição da ficha do cliente pelo escritório")
public record FichaClienteRequest(
    @Schema(description = "Observações internas do escritório (até 1000 caracteres); vazio apaga", example = "Prefere contato por e-mail")
    @Sensivel @Size(max = 1000) String observacoes) {

  @Override
  public String toString() {
    return "FichaClienteRequest[observacoes=" + (observacoes == null ? "null" : "***") + "]";
  }
}
