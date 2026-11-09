package br.com.agrocontabil.config;

import br.com.agrocontabil.service.DestinoEmailSeguro;
import br.com.agrocontabil.service.EnvioEmailFalhouException;
import br.com.agrocontabil.service.MensagensEmail.Mensagem;
import br.com.agrocontabil.service.TetoEnvioEmail;
import br.com.agrocontabil.service.TipoEmail;
import jakarta.mail.SendFailedException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.util.StringUtils;

/**
 * Envio real dos três e-mails pelo SMTP (issue #460), usado pelas portas no modo {@code SMTP}.
 *
 * <p>Na ordem: destino reservado (o seed é {@code @example.com}) não sai; o teto de envios
 * ({@link TetoEnvioEmail}) descarta acima da cota; a mensagem sai com remetente, {@code Reply-To},
 * assunto e texto em UTF-8. A falha vira {@link EnvioEmailFalhouException} sem o endereço, e falhas
 * seguidas de conexão ou autenticação (provedor fora do ar, chave revogada) dão um WARN, porque o
 * health do SMTP fica desligado: o log é o único sinal. Recusa de um destinatário não conta: é do
 * endereço, não do provedor.
 */
class EnvioEmailSmtp {

  private static final Logger log = LoggerFactory.getLogger(EnvioEmailSmtp.class);

  /** Falhas seguidas de conexão ou autenticação que disparam o primeiro WARN. */
  static final int FALHAS_PARA_AVISO = 3;

  /** Categorias que apontam o provedor (e contam nas falhas seguidas). */
  private static final List<String> PROVEDOR = List.of("conexao", "autenticacao");

  private final ObjectProvider<JavaMailSender> mailSender;
  private final String remetente;
  private final String replyTo;
  private final List<String> dominiosReservados;
  private final TetoEnvioEmail teto;
  private final AtomicInteger falhasSeguidas = new AtomicInteger();

  EnvioEmailSmtp(ObjectProvider<JavaMailSender> mailSender, String remetente,
      EmailProperties email, TetoEnvioEmail teto) {
    this.mailSender = mailSender;
    this.remetente = remetente;
    this.replyTo = email.replyTo();
    this.dominiosReservados = email.dominiosReservados();
    this.teto = teto;
  }

  /**
   * Envia o e-mail, se o destino e o teto permitirem.
   *
   * @param tipo         tipo do e-mail, para o log e o teto
   * @param destinatario endereço do destinatário (nunca vai ao log)
   * @param mensagem     assunto e texto
   * @throws EnvioEmailFalhouException se o provedor recusar ou não responder
   */
  void enviar(TipoEmail tipo, String destinatario, Mensagem mensagem) {
    if (DestinoEmailSeguro.reservado(destinatario, dominiosReservados)) {
      log.info("E-mail de {} nao enviado: destino em dominio reservado.", tipo);
      return;
    }
    if (!teto.reservar(tipo)) {
      return;
    }
    SimpleMailMessage email = new SimpleMailMessage();
    email.setFrom(remetente);
    if (StringUtils.hasText(replyTo)) {
      email.setReplyTo(replyTo);
    }
    email.setTo(destinatario);
    email.setSubject(mensagem.assunto());
    email.setText(mensagem.texto());
    try {
      mailSender.getObject().send(email);
      falhasSeguidas.set(0);
    } catch (MailException e) {
      String categoria = categoria(e);
      if (PROVEDOR.contains(categoria)) {
        int seguidas = falhasSeguidas.incrementAndGet();
        if (seguidas == FALHAS_PARA_AVISO || (seguidas > FALHAS_PARA_AVISO && seguidas % 10 == 0)) {
          log.warn("SMTP: {} falhas seguidas de {} (provedor fora do ar, credencial errada ou "
              + "cota esgotada). Os e-mails nao estao saindo.", seguidas, categoria);
        }
      }
      throw new EnvioEmailFalhouException("Falha no envio do e-mail de " + tipo + " (" + categoria
          + ": " + causas(e) + ").");
    }
  }

  /**
   * Autenticação, destinatário recusado ou conexão (o resto: provedor, rede, timeout, cota). Só é
   * "destinatário" a recusa que aponta endereço inválido: a {@code SendFailedException} também
   * chega na recusa do {@code MAIL FROM} ou do {@code DATA} (cota esgotada, remetente barrado),
   * que é do provedor.
   */
  static String categoria(MailException e) {
    if (e instanceof MailAuthenticationException) {
      return "autenticacao";
    }
    if (e instanceof MailPreparationException || e instanceof MailParseException) {
      // Mensagem ou endereco que o Jakarta Mail nao montou: nao chegou ao provedor.
      return "mensagem";
    }
    if (e instanceof MailSendException envio && !envio.getFailedMessages().isEmpty()
        && envio.getFailedMessages().values().stream()
            .allMatch(causa -> causa instanceof SendFailedException recusa
                && recusa.getInvalidAddresses() != null
                && recusa.getInvalidAddresses().length > 0)) {
      return "destinatario";
    }
    return "conexao";
  }

  /** Só os nomes das classes da cadeia de causas: as mensagens podem trazer o endereço. */
  static String causas(Throwable e) {
    List<String> nomes = new ArrayList<>();
    Throwable atual = e;
    while (atual != null && nomes.size() < 5) {
      nomes.add(atual.getClass().getSimpleName());
      atual = atual.getCause();
    }
    if (e instanceof MailSendException envio) {
      envio.getFailedMessages().values().stream().findFirst()
          .ifPresent(causa -> nomes.add(causa.getClass().getSimpleName()));
    }
    return String.join(" > ", nomes);
  }
}
