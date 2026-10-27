package br.com.agrocontabil.config;

import br.com.agrocontabil.service.EnvioNotificacao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Canal das notificações ao produtor (issue #182). Decisão do Murillo: na v1 só o registro no
 * banco, sem provedor de WhatsApp — a implementação abaixo não envia nada e não loga texto nem
 * telefone, só o id. O provedor real entra como outra implementação desta porta.
 */
@Configuration
public class NotificacaoConfig {

  private static final Logger log = LoggerFactory.getLogger(NotificacaoConfig.class);

  /**
   * Implementação de registro: a notificação já está gravada como {@code REGISTRADA}.
   *
   * @return a porta
   */
  @Bean
  public EnvioNotificacao envioNotificacao() {
    return (notificacao, destinatario) -> log.info(
        "Notificacao {} registrada (canal {}, sem envio real na v1)", notificacao.getId(),
        notificacao.getCanal());
  }
}
