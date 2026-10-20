package br.com.agrocontabil.config;

import br.com.agrocontabil.common.MascaraDadosPessoais;
import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * Conversor do Logback que mascara dados pessoais em toda mensagem de log (LGPD, issue #148).
 *
 * <p>Ligado em {@code logback-spring.xml} no lugar das palavras {@code %m}, {@code %msg} e
 * {@code %message}: o padrão de log do Spring Boot continua o mesmo, só a mensagem passa pela
 * {@link MascaraDadosPessoais}. O stack trace das exceções não passa por aqui — por isso as
 * mensagens de exceção do projeto nunca carregam documento nem e-mail.
 */
public class MensagemLogMascaradaConverter extends MessageConverter {

  @Override
  public String convert(ILoggingEvent evento) {
    return MascaraDadosPessoais.mascarar(super.convert(evento));
  }
}
