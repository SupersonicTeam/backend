package br.com.agrocontabil.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("ConferenciaEmail — configuracao de e-mail conferida na subida (#460)")
class ConferenciaEmailTest {

  private static final String DOMINIO = "agroconia.com.br";
  private static final String REMETENTE = "AgroConIA <nao-responda@agroconia.com.br>";

  private static MockEnvironment ambiente(String... perfis) {
    MockEnvironment ambiente = new MockEnvironment();
    ambiente.setActiveProfiles(perfis);
    return ambiente;
  }

  private static MockEnvironment smtp() {
    return ambiente("homolog")
        .withProperty("spring.mail.host", "smtp.provedor.test")
        .withProperty("spring.mail.username", "usuario")
        .withProperty("spring.mail.password", "senha")
        .withProperty("spring.mail.properties.mail.smtp.auth", "true");
  }

  private static JavaMailSender cliente(MockEnvironment ambiente) {
    StaticListableBeanFactory fabrica = new StaticListableBeanFactory();
    fabrica.addBean("mailSender", new JavaMailSenderImpl());
    return ConferenciaEmail.clienteSmtp(ambiente, fabrica.getBeanProvider(JavaMailSender.class),
        REMETENTE, DOMINIO);
  }

  @ParameterizedTest(name = "remetente aceito: {0}")
  @ValueSource(strings = {
      "AgroConIA <nao-responda@agroconia.com.br>",
      "nao-responda@agroconia.com.br",
      "\"AgroConIA\" <nao-responda@AgroConIA.com.br>",
      "  AgroConIA <nao-responda@agroconia.com.br>  "})
  void remetenteNoDominio(String remetente) {
    assertThatCode(() -> ConferenciaEmail.exigirRemetenteNoDominio(remetente, DOMINIO))
        .doesNotThrowAnyException();
  }

  @ParameterizedTest(name = "remetente recusado: [{0}]")
  @NullAndEmptySource
  @ValueSource(strings = {
      "AgroConIA <nao-responda@agroconia.local>",
      "nao-responda@agroconia.local",
      "AgroConIA <x@agroconia.com.br.evil.com>",
      "x@send.agroconia.com.br",
      "x@evil-agroconia.com.br",
      "a@agroconia.com.br, b@evil.com",
      "grupo: a@agroconia.com.br;",
      "AgroConIA",
      "AgroConIA <nao-responda>"})
  void remetenteForaDoDominio(String remetente) {
    assertThatThrownBy(() -> ConferenciaEmail.exigirRemetenteNoDominio(remetente, DOMINIO))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("EMAIL_REMETENTE");
  }

  @Test
  @DisplayName("SMTP completo devolve o cliente do Boot")
  void smtpCompleto() {
    assertThat(cliente(smtp())).isNotNull();
  }

  @Test
  @DisplayName("SMTP sem host, com host vazio ou em branco impede a subida")
  void smtpSemHost() {
    MockEnvironment semHost = ambiente("homolog")
        .withProperty("spring.mail.username", "usuario")
        .withProperty("spring.mail.password", "senha");
    for (MockEnvironment ambiente : new MockEnvironment[] {semHost,
        smtp().withProperty("spring.mail.host", ""),
        smtp().withProperty("spring.mail.host", "   ")}) {
      assertThatThrownBy(() -> cliente(ambiente))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("SPRING_MAIL_HOST");
    }
  }

  @Test
  @DisplayName("SMTP com autenticacao e usuario ou senha vazios impede a subida")
  void smtpSemCredencial() {
    assertThatThrownBy(() -> cliente(smtp().withProperty("spring.mail.username", "")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SPRING_MAIL_USERNAME");
    assertThatThrownBy(() -> cliente(smtp().withProperty("spring.mail.password", " ")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SPRING_MAIL_PASSWORD");
  }

  @Test
  @DisplayName("SMTP sem autenticacao (relay interno) dispensa usuario e senha")
  void smtpSemAutenticacao() {
    MockEnvironment ambiente = smtp()
        .withProperty("spring.mail.properties.mail.smtp.auth", "false")
        .withProperty("spring.mail.username", "")
        .withProperty("spring.mail.password", "");
    assertThat(cliente(ambiente)).isNotNull();
  }

  @Test
  @DisplayName("SMTP sem o cliente do Boot impede a subida")
  void smtpSemCliente() {
    assertThatThrownBy(() -> ConferenciaEmail.clienteSmtp(smtp(),
        new StaticListableBeanFactory().getBeanProvider(JavaMailSender.class), REMETENTE, DOMINIO))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("cliente SMTP");
  }

  @Test
  @DisplayName("HTTPS: prod sempre; fora de dev/test so com SMTP; dev e test aceitam http")
  void https() {
    String http = "http://localhost:4200/escritorio";
    assertThatThrownBy(() -> ConferenciaEmail.exigirHttps("x", http, false, ambiente("prod")))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("HTTPS");
    assertThatThrownBy(() -> ConferenciaEmail.exigirHttps("x", http, true, ambiente("homolog")))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> ConferenciaEmail.exigirHttps("x", http, true, ambiente()))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> ConferenciaEmail.exigirHttps("x", null, true, ambiente("homolog")))
        .isInstanceOf(IllegalStateException.class);
    assertThatCode(() -> ConferenciaEmail.exigirHttps("x", http, false, ambiente("homolog")))
        .doesNotThrowAnyException();
    assertThatCode(() -> ConferenciaEmail.exigirHttps("x", http, true, ambiente("dev")))
        .doesNotThrowAnyException();
    assertThatCode(() -> ConferenciaEmail.exigirHttps("x", http, true, ambiente("test")))
        .doesNotThrowAnyException();
    assertThatCode(() -> ConferenciaEmail.exigirHttps("x", "https://www.agroconia.com.br/x", true,
        ambiente("homolog"))).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("dominio verificado vazio, teto negativo ou links zerados impedem a subida")
  void propriedadesInvalidas() {
    assertThatThrownBy(() -> propriedades(" ", "contato@gwnext.com.br", 0, 80, 5))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> propriedades(DOMINIO, "contato@gwnext.com.br", -1, 80, 5))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> propriedades(DOMINIO, "contato@gwnext.com.br", 0, -1, 5))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> propriedades(DOMINIO, "contato@gwnext.com.br", 0, 80, 0))
        .isInstanceOf(IllegalStateException.class);
    assertThat(new EmailProperties(DOMINIO, "", 0, 0, 1, null).dominiosReservados()).isEmpty();
  }

  @Test
  @DisplayName("SMTP com Reply-To invalido impede a subida; vazio e aceito (sem Reply-To)")
  void replyTo() {
    StaticListableBeanFactory fabrica = new StaticListableBeanFactory();
    fabrica.addBean("mailSender", new JavaMailSenderImpl());
    assertThatThrownBy(() -> ConferenciaEmail.conferirSmtp(smtp(),
        fabrica.getBeanProvider(JavaMailSender.class), REMETENTE,
        propriedades(DOMINIO, "contato gwnext", 0, 80, 5)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("EMAIL_REPLY_TO");
    assertThatCode(() -> ConferenciaEmail.conferirSmtp(smtp(),
        fabrica.getBeanProvider(JavaMailSender.class), REMETENTE,
        propriedades(DOMINIO, "", 0, 80, 5))).doesNotThrowAnyException();
    assertThatCode(() -> ConferenciaEmail.conferirSmtp(smtp(),
        fabrica.getBeanProvider(JavaMailSender.class), REMETENTE,
        propriedades(DOMINIO, "contato@gwnext.com.br", 0, 80, 5))).doesNotThrowAnyException();
  }

  private static EmailProperties propriedades(String dominio, String replyTo, int porHora,
      int porDia, int links) {
    return new EmailProperties(dominio, replyTo, porHora, porDia, links, List.of());
  }
}
