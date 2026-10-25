package br.com.agrocontabil.dto;

import br.com.agrocontabil.model.FuncaoEscritorio;
import com.fasterxml.jackson.annotation.JsonIgnore;
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
public record ConviteMembroResponse(UUID id, String email, FuncaoEscritorio funcao,
    OffsetDateTime criadoEm, OffsetDateTime expiraEm) implements ResultadoAuditavel {

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
