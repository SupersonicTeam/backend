package br.com.agrocontabil.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * DTO de saída da apuração mensal consolidada de IBS/CBS de um produtor (issue Agrocontabil#41 —
 * RN004).
 *
 * <p>Consolida, por período mensal, o débito das saídas AUTORIZADAS e o crédito das entradas
 * IMPORTADAS, apurando o saldo devido ({@code débito − crédito}) de IBS e de CBS. Base legal:
 * não-cumulatividade e apuração periódica do IBS/CBS (EC 132/2023, art. 156-A; LC 214/2025).
 *
 * @param periodo        período consolidado no formato {@code YYYY-MM} (ex.: {@code 2026-07})
 * @param status         estado do ciclo de vida da apuração (ABERTO/EM_PROCESSAMENTO/FECHADO)
 * @param debitoIbs      débito de IBS das saídas autorizadas do período
 * @param debitoCbs      débito de CBS das saídas autorizadas do período
 * @param creditoIbs     crédito de IBS das entradas importadas do período
 * @param creditoCbs     crédito de CBS das entradas importadas do período
 * @param saldoIbs       saldo de IBS a recolher ({@code débito − crédito})
 * @param saldoCbs       saldo de CBS a recolher ({@code débito − crédito})
 * @param totalNotas     quantidade de notas consideradas na consolidação
 * @param dataFechamento instante do fechamento automático; nulo enquanto o período está aberto
 */
@Schema(description = "Apuração mensal consolidada de IBS/CBS de um produtor em um período")
public record ApuracaoResponse(

    @Schema(description = "Periodo consolidado (YYYY-MM)", example = "2026-07")
    String periodo,

    @Schema(description = "Estado da apuracao", example = "ABERTO")
    String status,

    @Schema(description = "Debito de IBS das saidas autorizadas", example = "150.00")
    BigDecimal debitoIbs,

    @Schema(description = "Debito de CBS das saidas autorizadas", example = "1350.00")
    BigDecimal debitoCbs,

    @Schema(description = "Credito de IBS das entradas importadas", example = "30.00")
    BigDecimal creditoIbs,

    @Schema(description = "Credito de CBS das entradas importadas", example = "270.00")
    BigDecimal creditoCbs,

    @Schema(description = "Saldo de IBS a recolher (debito - credito)", example = "120.00")
    BigDecimal saldoIbs,

    @Schema(description = "Saldo de CBS a recolher (debito - credito)", example = "1080.00")
    BigDecimal saldoCbs,

    @Schema(description = "Quantidade de notas consideradas na consolidacao", example = "8")
    long totalNotas,

    @Schema(description = "Instante do fechamento automatico (nulo se aberto)")
    OffsetDateTime dataFechamento) {}
