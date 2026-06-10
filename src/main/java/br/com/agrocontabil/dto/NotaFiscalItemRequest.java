package br.com.agrocontabil.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * DTO de entrada para um item de nota fiscal.
 *
 * <p>Todos os campos obrigatorios sao validados via Bean Validation.
 * O valorTotal do item nao e aceito do cliente — e calculado no mapper
 * (quantidade * valorUnitario, arredondado HALF_UP em 2 casas).
 */
@Schema(description = "Item de produto/servico a ser incluido na nota fiscal")
public record NotaFiscalItemRequest(

    @Schema(description = "Sequencial do item dentro da nota (1-based)", example = "1")
    @NotNull(message = "numeroItem e obrigatorio")
    @Positive(message = "numeroItem deve ser positivo")
    Integer numeroItem,

    @Schema(description = "Codigo interno do produto no sistema do emissor (opcional)",
        example = "PROD-001")
    @Size(max = 60, message = "codigoProduto deve ter no maximo 60 caracteres")
    String codigoProduto,

    @Schema(description = "Descricao do produto ou servico", example = "Soja em graos")
    @NotBlank(message = "descricao e obrigatoria")
    String descricao,

    @Schema(description = "Codigo NCM com 8 digitos numericos", example = "12010090")
    @NotBlank(message = "ncm e obrigatorio")
    @Pattern(regexp = "\\d{8}", message = "ncm deve ter exatamente 8 digitos numericos")
    String ncm,

    @Schema(description = "CFOP — Codigo Fiscal de Operacoes e Prestacoes (4 digitos)",
        example = "6101")
    @NotBlank(message = "cfop e obrigatorio")
    @Pattern(regexp = "\\d{4}", message = "cfop deve ter exatamente 4 digitos numericos")
    String cfop,

    @Schema(description = "Unidade comercial (ex.: UN, KG, CX)", example = "KG")
    @NotBlank(message = "unidade e obrigatoria")
    @Size(max = 6, message = "unidade deve ter no maximo 6 caracteres")
    String unidade,

    @Schema(description = "Quantidade comercializada", example = "500.0")
    @NotNull(message = "quantidade e obrigatoria")
    @Positive(message = "quantidade deve ser positiva")
    BigDecimal quantidade,

    @Schema(description = "Valor unitario do produto", example = "2.50")
    @NotNull(message = "valorUnitario e obrigatorio")
    @Positive(message = "valorUnitario deve ser positivo")
    BigDecimal valorUnitario
) {}
