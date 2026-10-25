package br.com.agrocontabil.dto;

import br.com.agrocontabil.model.SituacaoConvite;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Convite na visão do produtor (issue #177).
 *
 * @param id         convite
 * @param escritorio   nome do escritório que convidou
 * @param convidadoPor nome do contador que mandou o convite (#228)
 * @param crc          registro dele no conselho regional
 * @param situacao   situação do convite
 * @param criadoEm   quando foi enviado
 * @param expiraEm   até quando vale
 */
public record ConviteRecebidoResponse(
    UUID id, String escritorio, String convidadoPor, String crc, SituacaoConvite situacao,
    OffsetDateTime criadoEm,
    OffsetDateTime expiraEm) implements ResultadoAuditavel {

  @JsonIgnore
  @Override
  public Object status() {
    return situacao;
  }
}
