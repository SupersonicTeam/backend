package br.com.agrocontabil.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.UUID;

/**
 * Responsável pelo cliente depois da troca (issue #228).
 *
 * @param produtorId      cliente
 * @param responsavelId   membro responsável (nulo = nenhum)
 * @param responsavelNome nome do responsável
 */
public record ResponsavelClienteResponse(UUID produtorId, UUID responsavelId,
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
