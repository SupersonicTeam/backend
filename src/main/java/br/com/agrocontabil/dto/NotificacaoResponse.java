package br.com.agrocontabil.dto;

import br.com.agrocontabil.model.CanalNotificacao;
import br.com.agrocontabil.model.StatusNotificacao;
import br.com.agrocontabil.model.TipoNotificacao;
import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "Notificação registrada para o produtor")
public record NotificacaoResponse(
    @Schema(description = "Id da notificação", example = "3f2b8c1e-5d4a-4b7e-9a1c-2e8f0d6b7a10")
    UUID id,
    @Schema(description = "O que a notificação avisou")
    TipoNotificacao tipo,
    @Schema(description = "Canal da notificação", example = "WHATSAPP")
    CanalNotificacao canal,
    @Schema(description = "Status do registro (REGISTRADA)", example = "REGISTRADA")
    StatusNotificacao status,
    @Schema(description = "Texto do aviso", example = "Você tem 2 guias vencendo em breve.")
    String texto,
    @Schema(description = "Itens avisados", example = "2")
    int quantidadeItens,
    @Schema(description = "Quantos vencem hoje ou amanhã", example = "1")
    int urgentes,
    @Schema(description = "PRODUTOR, CONTADOR ou SISTEMA (aviso automático)", example = "SISTEMA")
    String disparadoPor,
    @Schema(description = "Quando foi registrada", example = "2026-09-01T10:00:00-03:00")
    OffsetDateTime criadoEm) {
}
