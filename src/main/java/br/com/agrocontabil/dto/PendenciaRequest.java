package br.com.agrocontabil.dto;

import br.com.agrocontabil.model.TipoDocumentoPendencia;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

/**
 * Pedido de documento do escritório ao produtor (issue #180).
 *
 * @param titulo         o que se pede
 * @param descricao      detalhes
 * @param dataLimite     prazo (opcional; com ele, entra no calendário como {@code solicitacao})
 * @param tiposDocumento tipos aceitos — lista, não string JSON
 * @param observacoes    observações ao produtor
 */
@Schema(description = "Pedido de documento do escritório ao produtor")
public record PendenciaRequest(
    @Schema(description = "O que se pede (até 120 caracteres)", example = "Extrato bancário de outubro")
    @NotBlank @Size(max = 120) String titulo,
    @Schema(description = "Detalhes do pedido (até 1000 caracteres)")
    @Size(max = 1000) String descricao,
    @Schema(description = "Prazo; com ele o pedido entra no calendário", example = "2026-11-30")
    LocalDate dataLimite,
    @Schema(description = "Tipos de documento aceitos (de 1 a 5)", example = "EXTRATO")
    @NotNull @NotEmpty @Size(max = 5) List<@NotNull TipoDocumentoPendencia> tiposDocumento,
    @Schema(description = "Observações ao produtor (até 1000 caracteres)")
    @Size(max = 1000) String observacoes) {
}
