package br.com.agrocontabil.dto;

import br.com.agrocontabil.model.SituacaoVinculo;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Resultado de suspender, reativar ou encerrar um cliente pelo escritório (issue #177).
 *
 * @param produtorId produtor
 * @param situacao   situação nova
 * @param inicio     início do vínculo
 * @param fim        fim (só encerrado)
 */
@Schema(description = "Situação do vínculo depois de suspender, reativar ou encerrar o cliente")
public record SituacaoVinculoResponse(
    @Schema(description = "Id do cliente", example = "3f2b8c1e-5d4a-4b7e-9a1c-2e8f0d6b7a10")
    UUID produtorId,
    @Schema(description = "Situação nova do vínculo", example = "SUSPENSO")
    SituacaoVinculo situacao,
    @Schema(description = "Início do vínculo", example = "2026-09-01T10:00:00-03:00")
    OffsetDateTime inicio,
    @Schema(description = "Fim do vínculo; só quando encerrado")
    OffsetDateTime fim)
    implements ResultadoAuditavel {

  @JsonIgnore
  @Override
  public Object status() {
    return situacao;
  }

  @JsonIgnore
  @Override
  public UUID produtorAuditado() {
    return produtorId;
  }
}
