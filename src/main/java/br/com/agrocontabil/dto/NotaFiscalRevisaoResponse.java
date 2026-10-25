package br.com.agrocontabil.dto;

import br.com.agrocontabil.model.AcaoRevisao;
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
public record NotaFiscalRevisaoResponse(UUID id, AcaoRevisao acao, String motivo, UUID usuarioId,
    String papel, OffsetDateTime ocorridoEm) {
}
