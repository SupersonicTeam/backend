package br.com.agrocontabil.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Devolução da nota pelo contador (issue #178).
 *
 * @param motivo o que o produtor precisa corrigir (5 a 500 caracteres)
 */
public record DevolucaoNotaRequest(@NotBlank @Size(min = 5, max = 500) String motivo) {
}
