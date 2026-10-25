package br.com.agrocontabil.dto;

import br.com.agrocontabil.model.TipoDocumentoPendencia;
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
public record PendenciaRequest(
    @NotBlank @Size(max = 120) String titulo,
    @Size(max = 1000) String descricao,
    LocalDate dataLimite,
    @NotNull @NotEmpty @Size(max = 5) List<@NotNull TipoDocumentoPendencia> tiposDocumento,
    @Size(max = 1000) String observacoes) {
}
