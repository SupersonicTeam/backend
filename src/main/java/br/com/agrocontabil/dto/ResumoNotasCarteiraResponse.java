package br.com.agrocontabil.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Contagem das notas da carteira por grupo de status (issue #341), uma consulta só, com os mesmos
 * filtros da lista. Os grupos são as caixas da tela de Notas do contador
 * ({@code CAIXAS_TRIAGEM}); o mapeamento status → grupo está em
 * {@link br.com.agrocontabil.service.GrupoNotasCarteira}.
 */
@Schema(description = "Quantas notas há em cada caixa de triagem, com os filtros da lista")
public record ResumoNotasCarteiraResponse(

    @Schema(description = "Precisam de revisão: AGUARDANDO_APROVACAO") long revisao,

    @Schema(description = "Travadas pelo fisco: COM_INCONSISTENCIAS e REJEITADA") long travadas,

    @Schema(description = "Rascunhos do produtor: RASCUNHO") long rascunhos,

    @Schema(description = "Devolvidas ao produtor: DEVOLVIDA") long devolvidas,

    @Schema(description = "Autorizadas: AUTORIZADA") long autorizadas,

    @Schema(description = "Canceladas: CANCELADA") long canceladas,

    @Schema(description = "Todas, inclusive os estados que nenhuma caixa mostra (APROVADA, "
        + "XML_GERADO, ENVIANDO, PENDENTE_SEFAZ, CANCELANDO)") long todas) {}
