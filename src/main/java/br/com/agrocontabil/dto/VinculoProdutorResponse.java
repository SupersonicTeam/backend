package br.com.agrocontabil.dto;

import br.com.agrocontabil.model.SituacaoVinculo;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Escritório que atende o produtor, na visão do produtor (issue #177). Sem as observações nem as
 * etiquetas do escritório.
 *
 * @param id          vínculo
 * @param escritorioId escritório (issue #228)
 * @param escritorio  nome do escritório
 * @param responsavel nome do contador responsável pelo produtor no escritório
 * @param crc         registro do responsável no conselho regional
 * @param situacao   situação do vínculo
 * @param inicio     início
 * @param fim        fim (só encerrado)
 */
public record VinculoProdutorResponse(
    UUID id, UUID escritorioId, String escritorio, String responsavel, String crc,
    SituacaoVinculo situacao,
    OffsetDateTime inicio, OffsetDateTime fim) implements ResultadoAuditavel {

  @JsonIgnore
  @Override
  public Object status() {
    return situacao;
  }
}
