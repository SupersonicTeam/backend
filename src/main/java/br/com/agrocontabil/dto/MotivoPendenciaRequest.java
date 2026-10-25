package br.com.agrocontabil.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Motivo de rejeitar ou cancelar uma pendência (issue #180).
 *
 * @param motivo explicação ao produtor (5 a 500 caracteres, sem contar os espaços das pontas)
 */
public record MotivoPendenciaRequest(@NotBlank @Size(min = 5, max = 500) String motivo) {

  /** Sem os espaços das pontas antes da validação. */
  public MotivoPendenciaRequest {
    motivo = motivo == null ? null : motivo.strip();
  }
}
