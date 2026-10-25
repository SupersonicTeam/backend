package br.com.agrocontabil.model;

/**
 * Situação do pedido de documento do escritório ao produtor (issue #180). Nomes do frontend.
 *
 * <pre>
 * PENDENTE --anexar (produtor)--> ENVIADA --concluir--> CONCLUIDA
 * ENVIADA --rejeitar {motivo}--> PENDENTE (os anexos ficam)
 * PENDENTE | ENVIADA --cancelar {motivo}--> CANCELADA
 * CONCLUIDA | CANCELADA --reabrir--> PENDENTE
 * </pre>
 */
public enum StatusPendencia {
  PENDENTE,
  ENVIADA,
  CONCLUIDA,
  CANCELADA
}
