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
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

@ExtendWith(OutputCaptureExtension.class)
@DisplayName("EnvioEmailSmtp — destino, teto, falhas e timeout do SMTP (#460)")
class EnvioEmailSmtpTest {

  private static final String DESTINO = "titular.secreto@fazenda.com.br";
  private static final MensagensEmail.Mensagem MENSAGEM =
      MensagensEmail.redefinicaoSenha("https://x/redefinir-senha#token=t", Duration.ofMinutes(30));

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
  @DisplayName("monta a mensagem com remetente, Reply-To, destino, assunto e texto")
  void montaMensagem() {
    List<SimpleMailMessage> enviadas = new ArrayList<>();
    JavaMailSender cliente = mock(JavaMailSender.class);
    doAnswer(i -> enviadas.add(i.getArgument(0))).when(cliente).send(any(SimpleMailMessage.class));

    envio(cliente, email(80)).enviar(TipoEmail.REDEFINICAO_SENHA, DESTINO, MENSAGEM);

    assertThat(enviadas).singleElement().satisfies(m -> {
      assertThat(m.getFrom()).isEqualTo("AgroConIA <nao-responda@agroconia.com.br>");
      assertThat(m.getReplyTo()).isEqualTo("contato@gwnext.com.br");
      assertThat(m.getTo()).containsExactly(DESTINO);
      assertThat(m.getSubject()).isEqualTo(MENSAGEM.assunto());
      assertThat(m.getText()).isEqualTo(MENSAGEM.texto());
    });
  }

  @Test
  @DisplayName("destino em dominio reservado nao sai e o log nao traz o endereco")
  void destinoReservado(CapturedOutput saida) {
    JavaMailSender cliente = mock(JavaMailSender.class);
    envio(cliente, email(80)).enviar(TipoEmail.CONVITE_CARTEIRA, "seed.produtor@example.com",
        MENSAGEM);
    verify(cliente, never()).send(any(SimpleMailMessage.class));
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
    verify(cliente, times(1)).send(any(SimpleMailMessage.class));
  }

  @Test
  @DisplayName("falha vira EnvioEmailFalhouException sem o endereco nem a causa")
  void falhaSemEndereco() throws Exception {
    JavaMailSender cliente = mock(JavaMailSender.class);
    SendFailedException recusa = new SendFailedException("550 <" + DESTINO + "> unknown",
        null, new InternetAddress[0], new InternetAddress[0],
        new InternetAddress[] {new InternetAddress(DESTINO)});
    doThrow(new MailSendException(Map.of(new Object(), recusa)))
        .when(cliente).send(any(SimpleMailMessage.class));

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
        .when(cliente).send(any(SimpleMailMessage.class));
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
