package br.com.agrocontabil.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * Responsável pelo cliente depois da troca (issue #228).
 *
 * @param produtorId      cliente
 * @param responsavelId   membro responsável (nulo = nenhum)
 * @param responsavelNome nome do responsável
 */
@Schema(description = "Responsável pelo cliente depois da troca")
public record ResponsavelClienteResponse(
    @Schema(description = "Id do cliente", example = "3f2b8c1e-5d4a-4b7e-9a1c-2e8f0d6b7a10")
    UUID produtorId,
    @Schema(description = "Membro responsável; nulo para nenhum", example = "3f2b8c1e-5d4a-4b7e-9a1c-2e8f0d6b7a10")
    UUID responsavelId,
    @Schema(description = "Nome do responsável", example = "Ana Souza")
    String responsavelNome) implements ResultadoAuditavel {

  @JsonIgnore
  @Override
  public Object status() {
    return responsavelId == null ? "SEM_RESPONSAVEL" : responsavelId;
  }

  @JsonIgnore
  @Override
  public UUID produtorAuditado() {
    return produtorId;
  }
}
