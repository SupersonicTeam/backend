package br.com.agrocontabil.dto;

import br.com.agrocontabil.model.SituacaoConvite;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Convite na visão do produtor (issue #177).
 *
 * @param id         convite
 * @param escritorio nome do escritório que convidou
 * @param crc        registro no conselho regional do escritório
 * @param situacao   situação do convite
 * @param criadoEm   quando foi enviado
 * @param expiraEm   até quando vale
 */
public record ConviteRecebidoResponse(
    UUID id, String escritorio, String crc, SituacaoConvite situacao, OffsetDateTime criadoEm,
    OffsetDateTime expiraEm) implements ResultadoAuditavel {

  @JsonIgnore
  @Override
  public Object status() {
    return situacao;
  }
}
