package br.com.agrocontabil.dto;

import br.com.agrocontabil.model.FuncaoEscritorio;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * Escritório do contador autenticado (issue #228).
 *
 * @param id          escritório
 * @param nome        nome
 * @param cnpj        CNPJ (opcional)
 * @param minhaFuncao função de quem pergunta
 * @param membros     quantos membros a equipe tem
 * @param slug        endereço próprio, {@code {slug}.agroconia.com.br} (issue #464); nulo = usa o
 *                    {@code www}
 * @param enderecoHost host completo do endereço próprio; nulo sem endereço
 * @param enderecoAlteravelEm instante a partir do qual o endereço pode ser gravado de novo; nulo =
 *                    já pode (remover é sempre permitido)
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
    long membros,
    @Schema(description = "Endereço próprio do escritório (rótulo do subdomínio); nulo = usa o www",
        example = "ruraloeste", nullable = true)
    String slug,
    @Schema(description = "Host do endereço próprio; nulo sem endereço",
        example = "ruraloeste.agroconia.com.br", nullable = true)
    String enderecoHost,
    @Schema(description = "A partir de quando o endereço pode ser gravado de novo (30 dias entre "
        + "trocas); nulo = já pode. Remover é sempre permitido", nullable = true)
    Instant enderecoAlteravelEm) implements ResultadoAuditavel {

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
