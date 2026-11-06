package br.com.agrocontabil.dto;

import br.com.agrocontabil.model.SituacaoConvite;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Convite pendente, na visão do escritório (issue #177). Não diz se o e-mail tem conta.
 *
 * @param id       convite
 * @param email    e-mail convidado
 * @param situacao sempre {@code PENDENTE} na lista
 * @param criadoEm quando foi (re)enviado
 * @param expiraEm até quando vale
 */
@Schema(description = "Convite pendente enviado pelo escritório a um produtor")
public record ConviteEnviadoResponse(
    @Schema(description = "Id do convite", example = "3f2b8c1e-5d4a-4b7e-9a1c-2e8f0d6b7a10")
    UUID id,
    @Schema(description = "E-mail do produtor convidado", example = "produtor@exemplo.com.br")
    String email,
    @Schema(description = "Situação do convite (PENDENTE na lista)", example = "PENDENTE")
    SituacaoConvite situacao,
    @Schema(description = "Quando o convite foi enviado ou reenviado", example = "2026-09-01T10:00:00-03:00")
    OffsetDateTime criadoEm,
    @Schema(description = "Até quando o convite vale", example = "2026-09-15T10:00:00-03:00")
    OffsetDateTime expiraEm) implements ResultadoAuditavel {

  @JsonIgnore
  @Override
  public Object status() {
    return situacao;
  }

  @JsonIgnore
  @Override
  public UUID recursoAuditado() {
    return id;
  }

  @Override
  public String toString() {
    return "ConviteEnviadoResponse[id=" + id + ", situacao=" + situacao + "]";
  }
}
