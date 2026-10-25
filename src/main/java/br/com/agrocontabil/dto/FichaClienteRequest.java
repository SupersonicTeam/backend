package br.com.agrocontabil.dto;

import br.com.agrocontabil.common.Sensivel;
import jakarta.validation.constraints.Size;

/**
 * Edição da ficha do cliente (issue #177). Observação vazia ou nula apaga a anterior.
 *
 * @param observacoes observações internas do escritório; {@link Sensivel} para o erro de tamanho
 *                    nunca ecoar o texto
 */
public record FichaClienteRequest(
    @Sensivel @Size(max = 1000) String observacoes) {

  @Override
  public String toString() {
    return "FichaClienteRequest[observacoes=" + (observacoes == null ? "null" : "***") + "]";
  }
}
