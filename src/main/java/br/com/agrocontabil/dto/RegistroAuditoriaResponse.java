package br.com.agrocontabil.dto;

import br.com.agrocontabil.model.AcaoAuditada;
import br.com.agrocontabil.model.RecursoAuditado;
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
public record RegistroAuditoriaResponse(
    UUID id,
    OffsetDateTime ocorridoEm,
    AcaoAuditada acao,
    RecursoAuditado recurso,
    UUID recursoId,
    UUID usuarioId,
    String papel,
    boolean sucesso,
    String codigoErro,
    String estadoAntes,
    String estadoDepois) {
}
