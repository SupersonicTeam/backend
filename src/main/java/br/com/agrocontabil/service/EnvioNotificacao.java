package br.com.agrocontabil.service;

import br.com.agrocontabil.model.Notificacao;

/**
 * Porta do canal real de notificação (issue #182), como o {@link EnvioEmail} da #209. Na v1 só
 * existe a implementação de registro ({@code config/NotificacaoConfig}): a notificação fica
 * {@code REGISTRADA} e nada sai do sistema. Um provedor de WhatsApp entra aqui.
 *
 * <p>Chamada depois do commit, fora de transação.
 *
 * <p><strong>Pré-condição da implementação que envia de verdade (issue #521, ponto 1).</strong> O
 * telefone do produtor chega pelo cadastro público sem prova de posse: qualquer pessoa pode
 * cadastrar o celular de outra. Antes de um provedor real entrar aqui, o número precisa estar
 * confirmado (código por SMS ou WhatsApp, refeito a cada troca de número), e só número confirmado
 * recebe mensagem — tanto o aviso do job das 9h quanto os disparos manuais, que levam texto livre.
 * Sem isso, a conta criada com o número de um terceiro manda a ele avisos fiscais, spam ou
 * phishing pelo número oficial. Enquanto só existir o registro, nada sai e a pré-condição não se
 * aplica.
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
