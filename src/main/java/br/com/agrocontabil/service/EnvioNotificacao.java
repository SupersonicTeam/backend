package br.com.agrocontabil.service;

import br.com.agrocontabil.model.Notificacao;

/**
 * Porta do canal real de notificação (issue #182), como o {@link EnvioEmail} da #209. Na v1 só
 * existe a implementação de registro ({@code config/NotificacaoConfig}): a notificação fica
 * {@code REGISTRADA} e nada sai do sistema. Um provedor de WhatsApp entra aqui.
 *
 * <p>Chamada depois do commit, fora de transação.
 */
@FunctionalInterface
public interface EnvioNotificacao {

  /**
   * Entrega a notificação já registrada.
   *
   * @param notificacao  a notificação
   * @param destinatario telefone do produtor (nunca vai ao log)
   */
  void enviar(Notificacao notificacao, String destinatario);
}
