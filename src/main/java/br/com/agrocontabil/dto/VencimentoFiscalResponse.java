package br.com.agrocontabil.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Item do calendário fiscal do produtor (issue #74 — {@code GET /api/calendario-fiscal}).
 *
 * <p>Nesta entrega a única fonte é a {@code GuiaFiscal} do produtor, mapeada para {@code tipo =
 * "imposto"}. Outros tipos de vencimento ({@code obrigacao}/{@code declaracao}/{@code
 * solicitacao}) ficam para quando o backend modelar outras fontes de prazo fiscal.
 *
 * <p><strong>Nota de integração:</strong> o {@code status} aqui é o valor real
 * ({@code pendente}/{@code pago}/{@code vencido}) — o mapeamento atual do frontend
 * ({@code calendario.service.ts}) só reconhece {@code "vencido"} e trata qualquer outro valor
 * como {@code "pendente"}, então uma guia {@code pago} aparece como pendente até o frontend
 * ajustar esse mapeamento (fora do escopo deste backend).
 *
 * @param id             identificador da guia de origem
 * @param evento         título exibido no calendário (ex.: "Guia ICMS")
 * @param descricao      observações da guia; pode ser nulo
 * @param dataVencimento data de vencimento
 * @param tipo           tipo do vencimento; sempre {@code "imposto"} nesta entrega
 * @param status         status do vencimento ({@code pendente}/{@code pago}/{@code vencido})
 * @param valor          valor da guia
 */
@Schema(description = "Item do calendario fiscal do produtor")
public record VencimentoFiscalResponse(

    @Schema(description = "Identificador da guia de origem")
    UUID id,

    @Schema(description = "Titulo exibido no calendario", example = "Guia ICMS")
    String evento,

    @Schema(description = "Observacoes da guia", example = "Referente a agosto/2026")
    String descricao,

    @Schema(description = "Data de vencimento", example = "2026-08-20")
    LocalDate dataVencimento,

    @Schema(description = "Tipo do vencimento", example = "imposto")
    String tipo,

    @Schema(description = "Status do vencimento (pendente/pago/vencido)", example = "pendente")
    String status,

    @Schema(description = "Valor do vencimento", example = "1250.00")
    BigDecimal valor) {}
