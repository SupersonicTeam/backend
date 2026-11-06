package br.com.agrocontabil.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Motivo de rejeitar ou cancelar uma pendência (issue #180).
 *
 * @param motivo explicação ao produtor (5 a 500 caracteres, sem contar os espaços das pontas)
 */
@Schema(description = "Motivo ao rejeitar ou cancelar um pedido de documento")
public record MotivoPendenciaRequest(
    @Schema(description = "Explicação ao produtor (5 a 500 caracteres)", example = "O arquivo enviado está ilegível")
    @NotBlank @Size(min = 5, max = 500) String motivo) {

  /** Sem os espaços das pontas antes da validação. */
  public MotivoPendenciaRequest {
    motivo = motivo == null ? null : motivo.strip();
  }
}
