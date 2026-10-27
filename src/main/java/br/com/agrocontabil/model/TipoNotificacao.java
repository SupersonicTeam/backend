package br.com.agrocontabil.model;

/** O que a notificação avisa (issue #182). */
public enum TipoNotificacao {
  /** Vencimentos dos próximos 7 dias, urgentes primeiro (botão "Notificar"). */
  PROXIMAS_OBRIGACOES,
  /** Guias vencidas e pedidos de documento com prazo passado. */
  ATRASADOS,
  /** Mensagem livre do produtor ou do escritório. */
  MENSAGEM,
  /** Aviso automático do job diário: um item que vence amanhã. */
  AVISO_URGENTE
}
