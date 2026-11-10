package br.com.agrocontabil.dto;

import br.com.agrocontabil.nfe.TributacaoIcms;
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
    @NotNull(message = "numeroItem é obrigatório")
    @Positive(message = "numeroItem deve ser positivo")
    Integer numeroItem,

    @Schema(description = "Codigo interno do produto no sistema do emissor (opcional)",
        example = "PROD-001")
    @Size(max = 60, message = "codigoProduto deve ter no máximo 60 caracteres")
    String codigoProduto,

    @Schema(description = "Descricao do produto ou servico", example = "Soja em graos")
    @NotBlank(message = "descrição é obrigatória")
    String descricao,

    @Schema(description = "Codigo NCM com 8 digitos numericos", example = "12019000")
    @NotBlank(message = "ncm é obrigatório")
    @Pattern(regexp = "\\d{8}", message = "ncm deve ter exatamente 8 dígitos numéricos")
    @ValidNcm
    String ncm,

    @Schema(description = "CFOP — Codigo Fiscal de Operacoes e Prestacoes (4 digitos)",
        example = "6101")
    @NotBlank(message = "cfop é obrigatório")
    @Pattern(regexp = "\\d{4}", message = "cfop deve ter exatamente 4 dígitos numéricos")
    @ValidCfop
    String cfop,

    @Schema(description = "Unidade comercial (ex.: UN, KG, CX)", example = "KG")
    @NotBlank(message = "unidade é obrigatória")
    @Size(max = 6, message = "unidade deve ter no máximo 6 caracteres")
    String unidade,

    @Schema(description = "Quantidade comercializada", example = "500.0")
    @NotNull(message = "quantidade é obrigatória")
    @Positive(message = "quantidade deve ser positiva")
    BigDecimal quantidade,

    @Schema(description = "Valor unitario do produto", example = "2.50")
    @NotNull(message = "valorUnitario é obrigatório")
    @Positive(message = "valorUnitario deve ser positivo")
    BigDecimal valorUnitario,

    // -------------------------------------------------------------------------
    // Campos opcionais de IBS/CBS — apenas classificatórios/informativos.
    // Valores calculados (valorIbs, valorCbs, baseCalculo, alíquotas) NÃO vêm
    // do cliente — são computados pelo TributacaoService.
    // Base legal: EC 132/2023 + LC 214/2025.
    // -------------------------------------------------------------------------

    @Schema(
        description =
            "CST IBS/CBS: Codigo de Situacao Tributaria (3 digitos). "
                + "Opcional: sem ele, sao os tres primeiros digitos do cClassTrib.",
        example = "200")
    @Pattern(
        regexp = "\\d{3}",
        message = "cstIbsCbs deve ter exatamente 3 dígitos numéricos")
    String cstIbsCbs,

    @Schema(
        description =
            "Codigo de Classificacao Tributaria — cClassTrib (6 digitos). "
                + "Opcional: sem ele, vale o da regra pelo NCM (tabela oficial, IT 2025.002). "
                + "Informado, a reducao e a oficial do codigo; a emissao confere que ele existe.",
        example = "200036")
    @Pattern(
        regexp = "\\d{6}",
        message = "cClassTrib deve ter exatamente 6 dígitos numéricos")
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
    BigDecimal percentualReducao,

    // -------------------------------------------------------------------------
    // ICMS: o sistema nao calcula. So o CST, combinado com o contador, entre os que o leiaute
    // aceita sem valores (ICMS40 com 40/41/50, ICMS51 e ICMS90; MOC 7.00, Anexo I).
    // -------------------------------------------------------------------------

    @Schema(
        description =
            "CST do ICMS (40, 41, 50, 51 ou 90), combinado com o contador. Opcional (vazio = nao "
                + "informado): sem ele, "
                + "a venda interna de producao propria (CFOP 5101) a destinatario com IE sai com "
                + "51 (diferimento total); qualquer outro caso sem CST e barrado na emissao "
                + "(422 ICMS_A_DEFINIR). Exportacao (CFOP 7xxx) exige 41.",
        example = "51")
    @Pattern(
        regexp = TributacaoIcms.CSTS_SEM_CALCULO_REGEX,
        message = "cstIcms deve ser 40, 41, 50, 51 ou 90 (o sistema não calcula ICMS)")
    String cstIcms,

    @Schema(description = "Origem da mercadoria (tabela Torig, 0 a 8). Opcional; padrao 0, "
        + "nacional.", example = "0")
    @Pattern(regexp = "|[0-8]", message = "origemIcms deve ser um dígito de 0 a 8")
    String origemIcms
) {

  /** Item sem os campos do ICMS (o CST padrao ou a definir), como antes do changeSet 0054. */
  public NotaFiscalItemRequest(Integer numeroItem, String codigoProduto, String descricao,
      String ncm, String cfop, String unidade, BigDecimal quantidade, BigDecimal valorUnitario,
      String cstIbsCbs, String cClassTrib, BigDecimal percentualReducao) {
    this(numeroItem, codigoProduto, descricao, ncm, cfop, unidade, quantidade, valorUnitario,
        cstIbsCbs, cClassTrib, percentualReducao, null, null);
  }
}
