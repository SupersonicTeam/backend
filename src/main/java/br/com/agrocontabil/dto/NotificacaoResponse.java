package br.com.agrocontabil.dto;

import br.com.agrocontabil.model.CanalNotificacao;
import br.com.agrocontabil.model.StatusNotificacao;
import br.com.agrocontabil.model.TipoNotificacao;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Notificação registrada (issue #182).
 *
 * @param id              notificação
 * @param tipo            o que avisou
 * @param canal           WHATSAPP
 * @param status          REGISTRADA (o envio real fica para o provedor)
 * @param texto           o texto do aviso
 * @param quantidadeItens itens avisados
 * @param urgentes        quantos venciam hoje ou amanhã
 * @param disparadoPor    {@code PRODUTOR}, {@code CONTADOR} ou {@code SISTEMA} (aviso automático)
 * @param criadoEm        quando
 */
public record NotificacaoResponse(
    UUID id,
    TipoNotificacao tipo,
    CanalNotificacao canal,
    StatusNotificacao status,
    String texto,
    int quantidadeItens,
    int urgentes,
    String disparadoPor,
    OffsetDateTime criadoEm) {
}
