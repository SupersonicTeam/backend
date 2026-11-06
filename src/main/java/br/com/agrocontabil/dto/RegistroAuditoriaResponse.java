package br.com.agrocontabil.dto;

import br.com.agrocontabil.model.AcaoAuditada;
import br.com.agrocontabil.model.RecursoAuditado;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Registro de auditoria (issue #149) como o contador o lê. Os mesmos campos da tabela: nenhum
 * deles é dado pessoal.
 *
 * @param id           id do registro
 * @param ocorridoEm   quando
 * @param acao         o que foi feito
 * @param recurso      tipo do recurso
 * @param recursoId    recurso afetado (nulo na importação em lote)
 * @param usuarioId    id do produtor ou do contador que agiu
 * @param papel        papel de quem agiu
 * @param sucesso      se a operação deu certo
 * @param codigoErro   código do erro, na falha
 * @param estadoAntes  estado do recurso antes (ex.: status da nota)
 * @param estadoDepois estado do recurso depois
 */
@Schema(description = "Registro de auditoria de uma operação crítica")
public record RegistroAuditoriaResponse(
    @Schema(description = "Id do registro", example = "3f2b8c1e-5d4a-4b7e-9a1c-2e8f0d6b7a10")
    UUID id,
    @Schema(description = "Quando ocorreu", example = "2026-09-01T10:00:00-03:00")
    OffsetDateTime ocorridoEm,
    @Schema(description = "Ação auditada")
    AcaoAuditada acao,
    @Schema(description = "Tipo do recurso afetado")
    RecursoAuditado recurso,
    @Schema(description = "Recurso afetado; nulo na importação em lote", example = "3f2b8c1e-5d4a-4b7e-9a1c-2e8f0d6b7a10")
    UUID recursoId,
    @Schema(description = "Id do produtor ou do contador que agiu", example = "3f2b8c1e-5d4a-4b7e-9a1c-2e8f0d6b7a10")
    UUID usuarioId,
    @Schema(description = "Papel de quem agiu", example = "CONTADOR")
    String papel,
    @Schema(description = "Se a operação deu certo", example = "true")
    boolean sucesso,
    @Schema(description = "Código do erro, na falha")
    String codigoErro,
    @Schema(description = "Estado do recurso antes da operação", example = "RASCUNHO")
    String estadoAntes,
    @Schema(description = "Estado do recurso depois da operação", example = "AUTORIZADA")
    String estadoDepois) {
}
