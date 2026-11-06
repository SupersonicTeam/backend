package br.com.agrocontabil.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Devolução da nota pelo contador (issue #178).
 *
 * @param motivo o que o produtor precisa corrigir (5 a 500 caracteres)
 */
@Schema(description = "Devolução de nota pelo contador ao produtor")
public record DevolucaoNotaRequest(
    @Schema(description = "O que o produtor precisa corrigir (5 a 500 caracteres)", example = "Falta o CFOP correto no item 2")
    @NotBlank @Size(min = 5, max = 500) String motivo) {

  /** Sem os espaços das pontas antes da validação: espaço não conta para os 5 caracteres. */
  public DevolucaoNotaRequest {
    motivo = motivo == null ? null : motivo.strip();
  }
}
