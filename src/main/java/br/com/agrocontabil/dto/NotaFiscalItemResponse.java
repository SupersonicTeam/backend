package br.com.agrocontabil.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * DTO de saída para um item de nota fiscal.
 *
 * <p>Inclui todos os campos do item, incluindo {@code valorTotal} calculado,
 * os campos IBS/CBS calculados pelo servidor (EC 132/2023 + LC 214/2025)
 * e os timestamps de auditoria.
 *
 * <p>Os campos IBS/CBS são nulos para itens em notas emitidas antes da
 * Sprint 2 – Sessão 4 (compatibilidade retroativa).
 */
@Schema(description = "Item de produto/servico de uma nota fiscal")
public record NotaFiscalItemResponse(

    @Schema(description = "Identificador unico do item",
        example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
    UUID id,

    @Schema(description = "Sequencial do item dentro da nota (1-based)", example = "1")
    Integer numeroItem,

    @Schema(description = "Codigo interno do produto no sistema do emissor", example = "PROD-001")
    String codigoProduto,

    @Schema(description = "Descricao do produto ou servico", example = "Soja em graos")
    String descricao,

    @Schema(description = "Codigo NCM com 8 digitos", example = "12019000")
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

    // -------------------------------------------------------------------------
    // Campos IBS/CBS (EC 132/2023 + LC 214/2025) — calculados pelo servidor.
    // Nulos para notas anteriores à Sprint 2 Sessão 4.
    // -------------------------------------------------------------------------

    @Schema(
        description =
            "CST IBS/CBS: Codigo de Situacao Tributaria (3 digitos). "
                + "Nulo quando nao informado na entrada.",
        example = "000")
    String cstIbsCbs,

    @Schema(
        description =
            "Codigo de Classificacao Tributaria — cClassTrib (6 digitos). "
                + "Nulo quando nao informado na entrada.",
        example = "200036")
    String cClassTrib,

    @Schema(
        description =
            "Percentual de reducao IBS/CBS informado na entrada (0-100). Nulo quando o item "
                + "nao informa: vale o da tabela oficial (percentualReducaoAplicado).",
        example = "0.00")
    BigDecimal percentualReducao,

    @Schema(
        description =
            "Reducao IBS/CBS efetivamente aplicada (0-100; 100 = aliquota zero): a informada ou "
                + "a da tabela oficial pelo NCM (IT 2025.002; LC 214/2025, arts. 125, 135, 137, "
                + "138 e 148). Use este campo na tela, nao um valor fixo. Issue #272.",
        example = "60.00")
    BigDecimal percentualReducaoAplicado,

    @Schema(
        description =
            "cClassTrib aplicado no calculo: o informado ou o da regra pelo NCM (200036 in natura, "
                + "200003 cesta basica, 200014 horticolas/frutas/ovos, 200038 insumos, 000001 "
                + "integral). Issue #272.",
        example = "200036")
    String cClassTribAplicado,

    @Schema(
        description =
            "Base de calculo IBS/CBS = valorTotal (a reducao e da aliquota, nao da base; #278). "
                + "Calculado pelo servidor. EC 132/2023; LC 214/2025, arts. 12-18.",
        example = "1250.00")
    BigDecimal baseCalculoIbsCbs,

    @Schema(
        description =
            "Aliquota do IBS aplicada (proporcao decimal, ex.: 0.0010 = 0,10 %). "
                + "Aliquota de referencia para o ano-teste 2026 (LC 214/2025, arts. 87-92).",
        example = "0.0010")
    BigDecimal aliquotaIbs,

    @Schema(
        description =
            "Valor do IBS = baseCalculoIbsCbs * aliquotaIbs * (1 - percentualReducaoAplicado/100). "
                + "Informativo em 2026 (ano-teste, LC 214/2025).",
        example = "1.25")
    BigDecimal valorIbs,

    @Schema(
        description =
            "Aliquota da CBS aplicada (proporcao decimal, ex.: 0.0090 = 0,90 %). "
                + "Aliquota de referencia para o ano-teste 2026 (LC 214/2025, arts. 87-92).",
        example = "0.0090")
    BigDecimal aliquotaCbs,

    @Schema(
        description =
            "Valor da CBS = baseCalculoIbsCbs * aliquotaCbs * (1 - percentualReducaoAplicado/100). "
                + "Informativo em 2026 (ano-teste, LC 214/2025).",
        example = "11.25")
    BigDecimal valorCbs,

    @Schema(
        description =
            "CST do ICMS informado (40, 41, 50, 51 ou 90). Nulo = nao informado: na venda interna "
                + "de producao propria (CFOP 5101) a destinatario com IE vale o 51 (diferimento); "
                + "fora disso a emissao responde 422 ICMS_A_DEFINIR. Na nota importada, o CST ou "
                + "o CSOSN do XML do fornecedor.",
        example = "51")
    String cstIcms,

    @Schema(description = "Origem da mercadoria (0 a 8); nula = 0, nacional", example = "0")
    String origemIcms,

    @Schema(description = "Data e hora de criacao do item")
    OffsetDateTime createdAt,

    @Schema(description = "Data e hora da ultima atualizacao do item")
    OffsetDateTime updatedAt
) {}
