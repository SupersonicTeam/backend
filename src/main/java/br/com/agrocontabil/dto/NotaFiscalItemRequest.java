package br.com.agrocontabil.dto;

import br.com.agrocontabil.validation.ValidCfop;
import br.com.agrocontabil.validation.ValidNcm;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * DTO de entrada para um item de nota fiscal.
 *
 * <p>Campos obrigatórios são validados via Bean Validation.
 * O {@code valorTotal} do item não é aceito do cliente — é calculado no mapper
 * ({@code quantidade × valorUnitario}, arredondado HALF_UP em 2 casas).
 *
 * <p>Os campos de IBS/CBS ({@code valorIbs}, {@code valorCbs}, {@code baseCalculoIbsCbs},
 * {@code aliquotaIbs}, {@code aliquotaCbs}) são <strong>calculados pelo servidor</strong> e
 * nunca aceitos do cliente (evita mass-assignment). Apenas campos informativos/classificatórios
 * ({@code cstIbsCbs}, {@code cClassTrib}, {@code percentualReducao}) são opcionais na entrada.
 *
 * <p>Base legal: EC 132/2023 + LC 214/2025.
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
    @ValidNcm
    String ncm,

    @Schema(description = "CFOP — Codigo Fiscal de Operacoes e Prestacoes (4 digitos)",
        example = "6101")
    @NotBlank(message = "cfop e obrigatorio")
    @Pattern(regexp = "\\d{4}", message = "cfop deve ter exatamente 4 digitos numericos")
    @ValidCfop
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
    BigDecimal valorUnitario,

    // -------------------------------------------------------------------------
    // Campos opcionais de IBS/CBS — apenas classificatórios/informativos.
    // Valores calculados (valorIbs, valorCbs, baseCalculo, alíquotas) NÃO vêm
    // do cliente — são computados pelo CalculoIbsCbsService.
    // Base legal: EC 132/2023 + LC 214/2025.
    // -------------------------------------------------------------------------

    @Schema(
        description =
            "CST IBS/CBS: Codigo de Situacao Tributaria (3 digitos). "
                + "Opcional; tabela definida pela LC 214/2025.",
        example = "000")
    @Pattern(
        regexp = "\\d{3}",
        message = "cstIbsCbs deve ter exatamente 3 digitos numericos")
    String cstIbsCbs,

    @Schema(
        description =
            "Codigo de Classificacao Tributaria — cClassTrib (6 digitos). "
                + "Opcional; definido pela LC 214/2025 e Nota Tecnica RFB/CONFAZ.",
        example = "010100")
    @Pattern(
        regexp = "\\d{6}",
        message = "cClassTrib deve ter exatamente 6 digitos numericos")
    String cClassTrib,

    @Schema(
        description =
            "Percentual de reducao da base de calculo IBS/CBS (0 a 100). "
                + "Opcional; default 0 (sem reducao). "
                + "Usado para regimes diferenciados de produtos agropecuarios "
                + "(LC 214/2025 — percentual exato por produto sera refinado em "
                + "sprint futura conforme regulamentacao complementar).",
        example = "0.00")
    @DecimalMin(value = "0", message = "percentualReducao deve ser entre 0 e 100")
    @DecimalMax(value = "100", message = "percentualReducao deve ser entre 0 e 100")
    BigDecimal percentualReducao
) {}
