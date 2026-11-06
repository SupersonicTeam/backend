package br.com.agrocontabil.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * Resultado de um disparo de notificação (issue #182). {@code enviados = 0} é resposta legítima:
 * não havia nada a avisar, e nada foi registrado.
 *
 * @param enviados      itens avisados (1 na mensagem livre)
 * @param urgentes      quantos vencem hoje ou amanhã
 * @param notificacaoId a notificação registrada, ou nulo quando não havia nada
 * @param produtorId    produtor avisado (só para a auditoria)
 */
@Schema(description = "Resultado de um disparo de notificação")
public record ResultadoNotificacaoResponse(
    @Schema(description = "Itens avisados (1 na mensagem livre); 0 quando não havia nada a avisar", example = "2")
    int enviados,
    @Schema(description = "Quantos vencem hoje ou amanhã", example = "1")
    int urgentes,
    @Schema(description = "Notificação registrada; nulo quando não havia nada a avisar", example = "3f2b8c1e-5d4a-4b7e-9a1c-2e8f0d6b7a10")
    UUID notificacaoId,
    @Schema(description = "Produtor avisado; uso interno da auditoria, não vai na resposta")
    @JsonIgnore UUID produtorId) implements ResultadoAuditavel {

  @JsonIgnore
  @Override
  public Object status() {
    return notificacaoId == null ? "NADA_A_AVISAR" : "REGISTRADA";
  }

  @JsonIgnore
  @Override
  public UUID recursoAuditado() {
    return notificacaoId;
  }

  @JsonIgnore
  @Override
  public UUID produtorAuditado() {
    return produtorId;
  }
}
