package br.com.agrocontabil.dto;

import br.com.agrocontabil.model.TipoDocumentoPendencia;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/**
 * Modelo de pedido de documento do escritório (issue #517). O frontend leva os campos ao
 * formulário do pedido ({@code POST /api/contador/produtores/{produtorId}/pendencias}) e calcula a
 * {@code dataLimite} = hoje (Brasília) + {@code prazoEmDias}.
 *
 * @param id             modelo
 * @param titulo         o que se pede
 * @param descricao      detalhes
 * @param tiposDocumento tipos aceitos
 * @param prazoEmDias    prazo sugerido, em dias a partir do pedido
 * @param observacoes    observações ao produtor
 */
@Schema(description = "Modelo de pedido de documento do escritório")
public record ModeloPedidoResponse(
    @Schema(description = "Id do modelo", example = "3f2b8c1e-5d4a-4b7e-9a1c-2e8f0d6b7a10")
    UUID id,
    @Schema(description = "O que se pede", example = "Extrato bancário do mês")
    String titulo,
    @Schema(description = "Detalhes do pedido",
        example = "Extrato de todas as contas do mês anterior")
    String descricao,
    @Schema(description = "Tipos de documento aceitos", example = "[\"EXTRATO\"]")
    List<TipoDocumentoPendencia> tiposDocumento,
    @Schema(description = "Prazo sugerido, em dias a partir do pedido; nulo = sem prazo",
        example = "10")
    Integer prazoEmDias,
    @Schema(description = "Observações ao produtor", example = "Pode mandar em PDF")
    String observacoes) implements ResultadoAuditavel {

  /** Sem estado a registrar: os textos do modelo nunca vão à auditoria. */
  @JsonIgnore
  @Override
  public Object status() {
    return null;
  }

  @JsonIgnore
  @Override
  public UUID recursoAuditado() {
    return id;
  }
}
