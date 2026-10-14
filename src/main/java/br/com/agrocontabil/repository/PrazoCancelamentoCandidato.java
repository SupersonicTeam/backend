package br.com.agrocontabil.repository;

import br.com.agrocontabil.model.TipoDocumentoFiscal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Nota própria autorizada candidata a alerta de prazo de cancelamento (issue #140). Projeção: o
 * job varre as emissões de cinco dias de todos os produtores e não precisa das notas inteiras
 * (itens, destinatário) gerenciadas na sessão.
 *
 * @param id            id da nota
 * @param produtorId    dono da nota
 * @param numero        número
 * @param serie         série
 * @param tipoDocumento NF-e ou NFA (rótulo do alerta)
 * @param dataEmissao   emissão (o prazo é emissão + 30 dias, RN006)
 */
public record PrazoCancelamentoCandidato(
    UUID id, UUID produtorId, Long numero, Integer serie, TipoDocumentoFiscal tipoDocumento,
    OffsetDateTime dataEmissao) {}
