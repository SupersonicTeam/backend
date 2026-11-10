package br.com.agrocontabil.dto;

import br.com.agrocontabil.model.OrigemNotaFiscal;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.model.TipoDocumentoFiscal;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Resumo de uma nota na listagem da carteira do escritório (issue #341). Sem itens, sem XML e sem
 * documento do produtor; o detalhe, a prévia da DANFE, aprovar e devolver seguem nas rotas por
 * cliente ({@code /api/contador/produtores/{produtorId}/notas/...}), com o {@code produtorId}
 * daqui.
 */
@Schema(description = "Resumo de uma nota da carteira do escritório, com o cliente (sem itens)")
public record NotaCarteiraResponse(

    @Schema(description = "Identificador da nota") UUID id,

    @Schema(description = "Cliente (produtor) dono da nota") UUID produtorId,

    @Schema(description = "Nome do cliente", example = "Fazenda Boa Vista") String produtorNome,

    @Schema(description = "Número da NF-e", example = "1") Long numero,

    @Schema(description = "Série da NF-e", example = "1") Integer serie,

    @Schema(description = "Natureza da operação", example = "Venda de producao rural")
    String naturezaOperacao,

    @Schema(description = "Situação da nota", example = "AGUARDANDO_APROVACAO")
    StatusNotaFiscal status,

    @Schema(description = "NFE ou NFA", example = "NFE") TipoDocumentoFiscal tipoDocumento,

    @Schema(description = "Emissão") OffsetDateTime dataEmissao,

    @Schema(description = "Nome do destinatário") String destinatarioNome,

    @Schema(description = "PROPRIA (venda do cliente) ou IMPORTADA (compra: o cliente é o"
        + " destinatário)", example = "PROPRIA") OrigemNotaFiscal origem,

    @Schema(description = "Fornecedor que emitiu a compra importada; nulo na nota própria. O"
        + " documento do emitente só vem no detalhe da nota", example = "Posto Rota 163")
    String emitenteNome,

    @Schema(description = "Total dos produtos") BigDecimal valorTotalProdutos,

    @Schema(description = "Total da nota") BigDecimal valorTotalNota,

    @Schema(description = "IBS da nota (nulo em nota antiga)") BigDecimal valorTotalIbs,

    @Schema(description = "CBS da nota (nulo em nota antiga)") BigDecimal valorTotalCbs,

    @Schema(description = "Chave de acesso (nula antes do XML)") String chaveAcesso,

    @Schema(description = "Criação") OffsetDateTime createdAt,

    @Schema(description = "Última atualização") OffsetDateTime updatedAt) {}
