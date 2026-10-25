package br.com.agrocontabil.dto;

import br.com.agrocontabil.model.SituacaoConvite;
import com.fasterxml.jackson.annotation.JsonIgnore;
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
public record ConviteEnviadoResponse(
    UUID id, String email, SituacaoConvite situacao, OffsetDateTime criadoEm,
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
