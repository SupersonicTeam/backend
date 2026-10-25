package br.com.agrocontabil.dto;

import br.com.agrocontabil.model.SituacaoVinculo;
import com.fasterxml.jackson.annotation.JsonIgnore;
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
public record SituacaoVinculoResponse(
    UUID produtorId, SituacaoVinculo situacao, OffsetDateTime inicio, OffsetDateTime fim)
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
