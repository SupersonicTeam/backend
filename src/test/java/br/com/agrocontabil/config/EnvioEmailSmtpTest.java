package br.com.agrocontabil.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import br.com.agrocontabil.service.EnvioEmailFalhouException;
import br.com.agrocontabil.service.MensagensEmail;
import br.com.agrocontabil.service.TetoEnvioEmail;
import br.com.agrocontabil.service.TipoEmail;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessagePreparator;

@ExtendWith(OutputCaptureExtension.class)
@DisplayName("EnvioEmailSmtp — destino, teto, falhas e timeout do SMTP (#460)")
class EnvioEmailSmtpTest {

  private static final String DESTINO = "titular.secreto@fazenda.com.br";
  private static final MensagensEmail.Mensagem MENSAGEM =
      MensagensEmail.redefinicaoSenha("https://x.test/redefinir-senha#token=t",
          Duration.ofMinutes(30), "contato@gwnext.com.br");

  private static EmailProperties email(int tetoPorDia) {
    return new EmailProperties("agroconia.com.br", "contato@gwnext.com.br", 0, tetoPorDia, 5,
        List.of("example.com", ".test"));
  }

  private static EnvioEmailSmtp envio(JavaMailSender cliente, EmailProperties email) {
    StaticListableBeanFactory fabrica = new StaticListableBeanFactory();
    fabrica.addBean("mailSender", cliente);
    return new EnvioEmailSmtp(fabrica.getBeanProvider(JavaMailSender.class),
        "AgroConIA <nao-responda@agroconia.com.br>", email,
        new TetoEnvioEmail(email, Clock.systemUTC()));
  }

  @Test
  @DisplayName("monta multipart/alternative: remetente, Reply-To, assunto, texto e HTML em UTF-8")
  void montaMensagem() throws Exception {
    List<MimeMessagePreparator> enviadas = new ArrayList<>();
    JavaMailSender cliente = mock(JavaMailSender.class);
    doAnswer(i -> enviadas.add(i.getArgument(0)))
        .when(cliente).send(any(MimeMessagePreparator.class));

    envio(cliente, email(80)).enviar(TipoEmail.REDEFINICAO_SENHA, DESTINO, MENSAGEM);

    assertThat(enviadas).hasSize(1);
    MimeMessage mime = new JavaMailSenderImpl().createMimeMessage();
    enviadas.get(0).prepare(mime);
    mime.saveChanges();
    InternetAddress remetente = (InternetAddress) mime.getFrom()[0];
    assertThat(remetente.getPersonal()).isEqualTo("AgroConIA");
    assertThat(remetente.getAddress()).isEqualTo("nao-responda@agroconia.com.br");
    assertThat(mime.getReplyTo()).extracting(Object::toString)
        .containsExactly("contato@gwnext.com.br");
    assertThat(mime.getAllRecipients()).extracting(Object::toString).containsExactly(DESTINO);
    assertThat(mime.getSubject()).isEqualTo(MENSAGEM.assunto());
    assertThat(mime.getHeader("Auto-Submitted")).containsExactly("auto-generated");
    MimeMultipart partes = (MimeMultipart) mime.getContent();
    assertThat(partes.getContentType()).startsWith("multipart/alternative");
    assertThat(partes.getCount()).isEqualTo(2);
    assertThat(partes.getBodyPart(0).getContentType()).startsWith("text/plain")
        .containsIgnoringCase("charset=UTF-8");
    assertThat(partes.getBodyPart(0).getContent()).isEqualTo(MENSAGEM.texto());
    assertThat(partes.getBodyPart(1).getContentType()).startsWith("text/html")
        .containsIgnoringCase("charset=UTF-8");
    assertThat(partes.getBodyPart(1).getContent()).isEqualTo(MENSAGEM.html());
  }

  @Test
  @DisplayName("destino em dominio reservado nao sai e o log nao traz o endereco")
  void destinoReservado(CapturedOutput saida) {
    JavaMailSender cliente = mock(JavaMailSender.class);
    envio(cliente, email(80)).enviar(TipoEmail.CONVITE_CARTEIRA, "seed.produtor@example.com",
        MENSAGEM);
    verify(cliente, never()).send(any(MimeMessagePreparator.class));
    assertThat(saida).contains("CONVITE_CARTEIRA nao enviado: destino em dominio reservado")
        .doesNotContain("seed.produtor");
  }

  @Test
  @DisplayName("acima do teto o e-mail e descartado sem chamar o provedor")
  void teto() {
    JavaMailSender cliente = mock(JavaMailSender.class);
    EnvioEmailSmtp envio = envio(cliente, email(1));
    envio.enviar(TipoEmail.CONVITE_EQUIPE, DESTINO, MENSAGEM);
    envio.enviar(TipoEmail.CONVITE_EQUIPE, DESTINO, MENSAGEM);
    verify(cliente, times(1)).send(any(MimeMessagePreparator.class));
  }

  @Test
  @DisplayName("falha vira EnvioEmailFalhouException sem o endereco nem a causa")
  void falhaSemEndereco() throws Exception {
    JavaMailSender cliente = mock(JavaMailSender.class);
    SendFailedException recusa = new SendFailedException("550 <" + DESTINO + "> unknown",
        null, new InternetAddress[0], new InternetAddress[0],
        new InternetAddress[] {new InternetAddress(DESTINO)});
    doThrow(new MailSendException(Map.of(new Object(), recusa)))
        .when(cliente).send(any(MimeMessagePreparator.class));

    assertThatThrownBy(() -> envio(cliente, email(80))
        .enviar(TipoEmail.REDEFINICAO_SENHA, DESTINO, MENSAGEM))
        .isInstanceOf(EnvioEmailFalhouException.class)
        .hasMessageContaining("destinatario")
        .hasMessageContaining("SendFailedException")
        .hasNoCause()
        .satisfies(e -> assertThat(e.getMessage()).doesNotContain("titular.secreto"));
  }

  @Test
  @DisplayName("categoria: autenticacao, destinatario so com endereco invalido, o resto conexao")
  void categoria() throws Exception {
    assertThat(EnvioEmailSmtp.categoria(new MailAuthenticationException("x")))
        .isEqualTo("autenticacao");
    assertThat(EnvioEmailSmtp.categoria(new MailParseException("endereco malformado")))
        .isEqualTo("mensagem");
    assertThat(EnvioEmailSmtp.categoria(new MailPreparationException("mensagem malformada")))
        .isEqualTo("mensagem");
    SendFailedException comEndereco = new SendFailedException("x", null, null, null,
        new InternetAddress[] {new InternetAddress("a@b.com")});
    assertThat(EnvioEmailSmtp.categoria(new MailSendException(Map.of(new Object(), comEndereco))))
        .isEqualTo("destinatario");
    // Recusa do MAIL FROM ou do DATA (cota, remetente): SendFailedException sem endereco invalido.
    assertThat(EnvioEmailSmtp.categoria(new MailSendException(
        Map.of(new Object(), new SendFailedException("554 quota")))))
        .isEqualTo("conexao");
    assertThat(EnvioEmailSmtp.categoria(new MailSendException("Mail server connection failed",
        new MessagingException("x"), Map.of(new Object(), new MessagingException("x")))))
        .isEqualTo("conexao");
  }

  @Test
  @DisplayName("tres falhas seguidas de conexao dao um WARN; recusa de destinatario nao conta")
  void falhasSeguidas(CapturedOutput saida) {
    JavaMailSender cliente = mock(JavaMailSender.class);
    doThrow(new MailAuthenticationException("535 bad credentials"))
        .when(cliente).send(any(MimeMessagePreparator.class));
    EnvioEmailSmtp envio = envio(cliente, email(0));
    for (int i = 0; i < EnvioEmailSmtp.FALHAS_PARA_AVISO; i++) {
      assertThatThrownBy(() -> envio.enviar(TipoEmail.REDEFINICAO_SENHA, DESTINO, MENSAGEM))
          .isInstanceOf(EnvioEmailFalhouException.class);
    }
    assertThat(saida).contains("SMTP: 3 falhas seguidas de autenticacao");
  }

  @Test
  @DisplayName("servidor que aceita e nao responde: a thread e liberada no timeout, sem endereco no "
      + "log")
  void timeoutLiberaAThread(CapturedOutput saida) throws Exception {
    try (ServerSocket mudo = new ServerSocket(0)) {
      List<Socket> aceitos = new CopyOnWriteArrayList<>();
      Thread aceitador = new Thread(() -> {
        try {
          while (!mudo.isClosed()) {
            aceitos.add(mudo.accept());
          }
        } catch (Exception fim) {
          // socket fechado no fim do teste
        }
      });
      aceitador.setDaemon(true);
      aceitador.start();

      JavaMailSenderImpl cliente = new JavaMailSenderImpl();
      cliente.setHost("127.0.0.1");
      cliente.setPort(mudo.getLocalPort());
      Properties propriedades = new Properties();
      propriedades.put("mail.smtp.connectiontimeout", "1000");
      propriedades.put("mail.smtp.timeout", "1000");
      propriedades.put("mail.smtp.writetimeout", "1000");
      cliente.setJavaMailProperties(propriedades);

      EnvioEmailSmtp envio = envio(cliente, email(80));
      assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
          assertThatThrownBy(() -> envio.enviar(TipoEmail.REDEFINICAO_SENHA, DESTINO, MENSAGEM))
              .isInstanceOf(EnvioEmailFalhouException.class)
              .hasMessageContaining("conexao"));
      for (Socket socket : aceitos) {
        socket.close();
      }
    }
    assertThat(saida).doesNotContain("titular.secreto");
  }
}
