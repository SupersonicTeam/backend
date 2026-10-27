package br.com.agrocontabil.model;

/**
 * Situação da notificação (issue #182). Na v1 o envio real não existe: toda notificação nasce e
 * fica {@code REGISTRADA}. Os status de entrega entram junto com o provedor de WhatsApp.
 */
public enum StatusNotificacao {
  REGISTRADA
}
