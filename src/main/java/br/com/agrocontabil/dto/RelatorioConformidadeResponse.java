package br.com.agrocontabil.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

/**
 * DTO de saída do relatório de conformidade tributária de um produtor em um período
 * (issue Agrocontabil#32 — UC004).
 *
 * <p>Consolida os <strong>débitos</strong> de IBS/CBS das notas de saída autorizadas com os
 * <strong>créditos</strong> das notas de entrada (origem IMPORTADA), apurando o saldo a pagar
 * do período. Base legal: apuração não-cumulativa do IBS/CBS (EC 132/2023, art. 156-A;
 * LC 214/2025, arts. 47–51).
 *
 * <p>O {@code saldoAPagar} pode ser <strong>negativo</strong> — nesse caso representa um saldo
 * credor a transportar para o período seguinte (crédito acumulado maior que o débito).
 *
 * @param periodo            período consultado no formato {@code YYYY-MM}
 * @param debitoIbs          débito de IBS das saídas autorizadas
 * @param debitoCbs          débito de CBS das saídas autorizadas
 * @param debitoTotal        soma dos débitos de IBS e CBS
 * @param quantidadeSaidas   quantidade de notas de saída autorizadas consideradas
 * @param creditoIbs         crédito de IBS das entradas
 * @param creditoCbs         crédito de CBS das entradas
 * @param creditoTotal       soma dos créditos de IBS e CBS
 * @param quantidadeEntradas quantidade de notas de entrada consideradas
 * @param saldoIbs           saldo de IBS ({@code debitoIbs − creditoIbs})
 * @param saldoCbs           saldo de CBS ({@code debitoCbs − creditoCbs})
 * @param saldoAPagar        saldo total a pagar ({@code debitoTotal − creditoTotal}); negativo = credor
 */
@Schema(description = "Relatorio de conformidade tributaria (debitos x creditos) de um periodo")
public record RelatorioConformidadeResponse(

    @Schema(description = "Periodo consultado (YYYY-MM)", example = "2026-07")
    String periodo,

    @Schema(description = "Debito de IBS das saidas autorizadas", example = "10.00")
    BigDecimal debitoIbs,

    @Schema(description = "Debito de CBS das saidas autorizadas", example = "90.00")
    BigDecimal debitoCbs,

    @Schema(description = "Soma dos debitos de IBS e CBS", example = "100.00")
    BigDecimal debitoTotal,

    @Schema(description = "Quantidade de notas de saida autorizadas", example = "5")
    long quantidadeSaidas,

    @Schema(description = "Credito de IBS das entradas", example = "4.00")
    BigDecimal creditoIbs,

    @Schema(description = "Credito de CBS das entradas", example = "36.00")
    BigDecimal creditoCbs,

    @Schema(description = "Soma dos creditos de IBS e CBS", example = "40.00")
    BigDecimal creditoTotal,

    @Schema(description = "Quantidade de notas de entrada", example = "2")
    long quantidadeEntradas,

    @Schema(description = "Saldo de IBS (debito - credito)", example = "6.00")
    BigDecimal saldoIbs,

    @Schema(description = "Saldo de CBS (debito - credito)", example = "54.00")
    BigDecimal saldoCbs,

    @Schema(description = "Saldo total a pagar (negativo = saldo credor)", example = "60.00")
    BigDecimal saldoAPagar) {}
