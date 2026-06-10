package br.com.agrocontabil.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * DTO de saida para um item de nota fiscal.
 *
 * <p>Inclui todos os campos do item, incluindo {@code valorTotal} calculado
 * e os timestamps de auditoria.
 */
@Schema(description = "Item de produto/servico de uma nota fiscal")
public record NotaFiscalItemResponse(

    @Schema(description = "Identificador unico do item", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
    UUID id,

    @Schema(description = "Sequencial do item dentro da nota (1-based)", example = "1")
    Integer numeroItem,

    @Schema(description = "Codigo interno do produto no sistema do emissor", example = "PROD-001")
    String codigoProduto,

    @Schema(description = "Descricao do produto ou servico", example = "Soja em graos")
    String descricao,

    @Schema(description = "Codigo NCM com 8 digitos", example = "12010090")
    String ncm,

    @Schema(description = "CFOP — Codigo Fiscal de Operacoes e Prestacoes", example = "6101")
    String cfop,

    @Schema(description = "Unidade comercial", example = "KG")
    String unidade,

    @Schema(description = "Quantidade comercializada", example = "500.0000")
    BigDecimal quantidade,

    @Schema(description = "Valor unitario do produto", example = "2.5000")
    BigDecimal valorUnitario,

    @Schema(description = "Valor total do item (quantidade * valorUnitario)", example = "1250.00")
    BigDecimal valorTotal,

    @Schema(description = "Data e hora de criacao do item")
    OffsetDateTime createdAt,

    @Schema(description = "Data e hora da ultima atualizacao do item")
    OffsetDateTime updatedAt
) {}
