package br.com.agrocontabil.dto;

import br.com.agrocontabil.model.AcaoRevisao;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Uma ação do histórico de revisão (issue #178).
 *
 * @param id         ação
 * @param acao       ENVIADA, RETIRADA, APROVADA, DEVOLVIDA ou APROVACAO_CADUCADA
 * @param motivo     motivo da devolução
 * @param usuarioId  quem agiu (nulo na caducidade)
 * @param papel      PRODUTOR ou CONTADOR
 * @param ocorridoEm quando
 */
@Schema(description = "Uma ação do histórico de revisão de nota")
public record NotaFiscalRevisaoResponse(
    @Schema(description = "Id da ação", example = "3f2b8c1e-5d4a-4b7e-9a1c-2e8f0d6b7a10")
    UUID id,
    @Schema(description = "ENVIADA, RETIRADA, APROVADA, DEVOLVIDA ou APROVACAO_CADUCADA", example = "ENVIADA")
    AcaoRevisao acao,
    @Schema(description = "Motivo da devolução, quando houver")
    String motivo,
    @Schema(description = "Quem agiu; nulo na caducidade", example = "3f2b8c1e-5d4a-4b7e-9a1c-2e8f0d6b7a10")
    UUID usuarioId,
    @Schema(description = "PRODUTOR ou CONTADOR", example = "PRODUTOR")
    String papel,
    @Schema(description = "Quando aconteceu", example = "2026-09-01T10:00:00-03:00")
    OffsetDateTime ocorridoEm) {
}
