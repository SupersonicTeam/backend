package br.com.agrocontabil.dto;

import br.com.agrocontabil.model.Periodicidade;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Item do calendário fiscal do produtor (issues #74 e #181).
 *
 * <p>{@code tipo = "imposto"} é uma guia; {@code tipo = "obrigacao"} é uma ocorrência derivada do
 * catálogo de obrigações (ou o fechamento da apuração), sem status nem valor.
 */
@Schema(description = "Vencimento do calendario fiscal")
public record VencimentoFiscalResponse(

    @Schema(description = "Id da guia; na obrigacao, <obrigacaoId>:<data>")
    String id,

    @Schema(description = "Titulo exibido no calendario", example = "Guia ICMS")
    String evento,

    @Schema(description = "Observacoes da guia ou descricao da obrigacao")
    String descricao,

    @Schema(description = "Data de vencimento", example = "2026-08-20")
    LocalDate dataVencimento,

    @Schema(description = "imposto (guia) ou obrigacao", example = "imposto")
    String tipo,

    @Schema(description = "Status da guia (pendente/pago/vencido); nulo na obrigacao",
        example = "pendente")
    String status,

    @Schema(description = "Valor da guia; nulo na obrigacao", example = "1250.00")
    BigDecimal valor,

    @Schema(description = "Titulo (igual a evento; contrato da #181)")
    String titulo,

    @Schema(description = "MENSAL ou ANUAL, so na obrigacao")
    Periodicidade periodicidade,

    @Schema(description = "Fundamento legal, so na obrigacao")
    String origem) {}
