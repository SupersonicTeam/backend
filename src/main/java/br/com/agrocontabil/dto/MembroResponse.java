package br.com.agrocontabil.dto;

import br.com.agrocontabil.model.FuncaoEscritorio;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.UUID;

/**
 * Membro da equipe do escritório (issue #228). Sem e-mail, telefone nem documento do colega.
 *
 * @param id           contador
 * @param nomeCompleto nome
 * @param crc          registro no conselho regional
 * @param funcao       função no escritório
 */
public record MembroResponse(UUID id, String nomeCompleto, String crc, FuncaoEscritorio funcao)
    implements ResultadoAuditavel {

  @JsonIgnore
  @Override
  public Object status() {
    return funcao == null ? "REMOVIDO" : funcao;
  }

  @JsonIgnore
  @Override
  public UUID recursoAuditado() {
    return id;
  }
}
