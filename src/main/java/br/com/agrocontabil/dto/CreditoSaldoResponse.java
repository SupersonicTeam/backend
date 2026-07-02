package br.com.agrocontabil.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

/**
 * DTO de saída do saldo acumulado de crédito tributário de IBS/CBS de um produtor em um
 * período (issue Agrocontabil#30 — RN003).
 *
 * <p>Consolida os créditos aproveitáveis das notas de entrada (origem IMPORTADA) do produtor
 * autenticado emitidas no mês informado. Base legal: não-cumulatividade do IBS/CBS
 * (EC 132/2023, art. 156-A, § 1.º, VIII; LC 214/2025, art. 47).
 *
 * @param periodo         período consultado no formato {@code YYYY-MM} (ex.: {@code 2026-07})
 * @param creditoIbs      somatório do crédito de IBS aproveitável no período (nunca nulo)
 * @param creditoCbs      somatório do crédito de CBS aproveitável no período (nunca nulo)
 * @param creditoTotal    soma de {@code creditoIbs} e {@code creditoCbs}
 * @param quantidadeNotas quantidade de notas de entrada consideradas no período
 */
@Schema(description = "Saldo acumulado de crédito tributário de IBS/CBS em um período")
public record CreditoSaldoResponse(

    @Schema(description = "Periodo consultado (YYYY-MM)", example = "2026-07")
    String periodo,

    @Schema(description = "Somatorio do credito de IBS aproveitavel no periodo", example = "12.50")
    BigDecimal creditoIbs,

    @Schema(description = "Somatorio do credito de CBS aproveitavel no periodo", example = "112.50")
    BigDecimal creditoCbs,

    @Schema(description = "Soma dos creditos de IBS e CBS no periodo", example = "125.00")
    BigDecimal creditoTotal,

    @Schema(description = "Quantidade de notas de entrada consideradas no periodo", example = "3")
    long quantidadeNotas) {}
