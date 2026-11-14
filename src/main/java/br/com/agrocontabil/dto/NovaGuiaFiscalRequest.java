package br.com.agrocontabil.dto;

import br.com.agrocontabil.model.TipoGuiaFiscal;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Corpo da criação de guia nas rotas sem {@code produtorId} no corpo (issue #273): {@code POST
 * /api/guias} (o produtor vem da identidade autenticada) e {@code POST
 * /api/contador/produtores/{produtorId}/guias} (o produtor vem do path, pela carteira).
 *
 * <p>Um {@code produtorId} que venha no JSON é ignorado: o alvo nunca é decidido pelo corpo.
 *
 * @param tipoGuia       tipo da guia (ICMS/FUNRURAL/DARF/GPS/OUTRO)
 * @param dataVencimento data de vencimento da guia
 * @param valor          valor da guia
 * @param observacoes    observações livres; opcional
 */
@Schema(description = "Dados de uma guia fiscal; o produtor vem da identidade ou do path, "
    + "nunca do corpo")
public record NovaGuiaFiscalRequest(

    @Schema(description = "Tipo da guia fiscal", example = "ICMS")
    @NotNull(message = "O tipoGuia é obrigatório.")
    TipoGuiaFiscal tipoGuia,

    @Schema(description = "Data de vencimento da guia", example = "2026-08-20")
    @NotNull(message = "A dataVencimento é obrigatória.")
    LocalDate dataVencimento,

    @Schema(description = "Valor da guia", example = "1250.00")
    @NotNull(message = "O valor é obrigatório.")
    @DecimalMin(value = "0.01", message = "O valor deve ser maior que zero.")
    BigDecimal valor,

    @Schema(description = "Observacoes livres", example = "Referente a agosto/2026")
    @Size(max = 255, message = "As observações devem ter no máximo 255 caracteres.")
    String observacoes) {

  /** O mesmo pedido com o produtor já decidido pelo service. */
  public GuiaFiscalRequest comProdutor(UUID produtorId) {
    return new GuiaFiscalRequest(produtorId, tipoGuia, dataVencimento, valor, observacoes);
  }
}
