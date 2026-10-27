package br.com.agrocontabil.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
public record ResultadoNotificacaoResponse(
    int enviados,
    int urgentes,
    UUID notificacaoId,
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
