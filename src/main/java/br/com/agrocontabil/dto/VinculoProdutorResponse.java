package br.com.agrocontabil.dto;

import br.com.agrocontabil.model.SituacaoVinculo;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Escritório que atende o produtor, na visão do produtor (issue #177). Sem as observações nem as
 * etiquetas do escritório.
 *
 * @param id         vínculo
 * @param contadorId escritório
 * @param escritorio nome do escritório
 * @param crc        registro no conselho regional
 * @param situacao   situação do vínculo
 * @param inicio     início
 * @param fim        fim (só encerrado)
 */
public record VinculoProdutorResponse(
    UUID id, UUID contadorId, String escritorio, String crc, SituacaoVinculo situacao,
    OffsetDateTime inicio, OffsetDateTime fim) implements ResultadoAuditavel {

  @JsonIgnore
  @Override
  public Object status() {
    return situacao;
  }
}
