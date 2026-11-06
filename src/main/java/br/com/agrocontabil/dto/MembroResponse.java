package br.com.agrocontabil.dto;

import br.com.agrocontabil.model.FuncaoEscritorio;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * Membro da equipe do escritório (issue #228). Sem e-mail, telefone nem documento do colega.
 *
 * @param id           contador
 * @param nomeCompleto nome
 * @param crc          registro no conselho regional
 * @param funcao       função no escritório
 */
@Schema(description = "Membro da equipe do escritório, sem contato nem documento")
public record MembroResponse(
    @Schema(description = "Id do contador", example = "3f2b8c1e-5d4a-4b7e-9a1c-2e8f0d6b7a10")
    UUID id,
    @Schema(description = "Nome do contador", example = "Ana Souza")
    String nomeCompleto,
    @Schema(description = "Registro no conselho regional", example = "MT-12345")
    String crc,
    @Schema(description = "Função no escritório", example = "CONTADOR")
    FuncaoEscritorio funcao)
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
