package br.com.agrocontabil.dto;

import br.com.agrocontabil.model.FuncaoEscritorio;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Convite de equipe pendente, na visão do administrador (issue #228). Nunca o token.
 *
 * @param id       convite
 * @param email    e-mail convidado
 * @param funcao   função oferecida
 * @param criadoEm quando foi enviado
 * @param expiraEm até quando vale
 */
@Schema(description = "Convite de equipe pendente, na visão do administrador (sem o token)")
public record ConviteMembroResponse(
    @Schema(description = "Id do convite", example = "3f2b8c1e-5d4a-4b7e-9a1c-2e8f0d6b7a10")
    UUID id,
    @Schema(description = "E-mail do convidado", example = "colega@exemplo.com.br")
    String email,
    @Schema(description = "Função oferecida", example = "CONTADOR")
    FuncaoEscritorio funcao,
    @Schema(description = "Quando o convite foi enviado", example = "2026-09-01T10:00:00-03:00")
    OffsetDateTime criadoEm,
    @Schema(description = "Até quando o convite vale", example = "2026-09-15T10:00:00-03:00")
    OffsetDateTime expiraEm) implements ResultadoAuditavel {

  @JsonIgnore
  @Override
  public Object status() {
    return funcao;
  }

  @JsonIgnore
  @Override
  public UUID recursoAuditado() {
    return id;
  }

  @Override
  public String toString() {
    return "ConviteMembroResponse[id=" + id + ", funcao=" + funcao + "]";
  }
}
