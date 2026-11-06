package br.com.agrocontabil.dto;

import br.com.agrocontabil.model.FuncaoEscritorio;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * Escritório do contador autenticado (issue #228).
 *
 * @param id          escritório
 * @param nome        nome
 * @param cnpj        CNPJ (opcional)
 * @param minhaFuncao função de quem pergunta
 * @param membros     quantos membros a equipe tem
 */
@Schema(description = "Escritório do contador autenticado")
public record EscritorioResponse(
    @Schema(description = "Id do escritório", example = "3f2b8c1e-5d4a-4b7e-9a1c-2e8f0d6b7a10")
    UUID id,
    @Schema(description = "Nome do escritório", example = "Escritório Campo Verde")
    String nome,
    @Schema(description = "CNPJ do escritório, se houver, só com dígitos")
    String cnpj,
    @Schema(description = "Função de quem consulta", example = "ADMINISTRADOR")
    FuncaoEscritorio minhaFuncao,
    @Schema(description = "Quantos membros tem a equipe", example = "3")
    long membros) implements ResultadoAuditavel {

  @JsonIgnore
  @Override
  public Object status() {
    return "ATUALIZADO";
  }

  @JsonIgnore
  @Override
  public UUID recursoAuditado() {
    return id;
  }
}
