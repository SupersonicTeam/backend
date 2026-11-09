package br.com.agrocontabil.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.agrocontabil.config.RedefinicaoSenhaProperties.ModoEmail;
import br.com.agrocontabil.service.TetoEnvioEmail;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("EmailConfig — guardas do envio do link de redefinicao (#209, #460)")
class EmailConfigTest {

  private static final EmailProperties DOMINIO = new EmailProperties("agroconia.com.br",
      "contato@gwnext.com.br", 0, 80, 5, List.of());

  private static RedefinicaoSenhaProperties props(ModoEmail modo, String url) {
    return props(modo, url, "AgroConIA <nao-responda@agroconia.com.br>");
  }

  private static RedefinicaoSenhaProperties props(ModoEmail modo, String url, String remetente) {
    return new RedefinicaoSenhaProperties(Duration.ofMinutes(30), url, modo, remetente);
  }

  private static MockEnvironment perfis(String... perfis) {
    MockEnvironment ambiente = new MockEnvironment();
    ambiente.setActiveProfiles(perfis);
    return ambiente;
  }

  private static MockEnvironment smtpCompleto(String... perfis) {
    return perfis(perfis)
        .withProperty("spring.mail.host", "smtp.provedor.test")
        .withProperty("spring.mail.username", "usuario")
        .withProperty("spring.mail.password", "senha")
        .withProperty("spring.mail.properties.mail.smtp.auth", "true");
  }

  private static void criar(RedefinicaoSenhaProperties props, MockEnvironment ambiente) {
    criar(props, ambiente, new StaticListableBeanFactory());
  }

  private static void criarComCliente(RedefinicaoSenhaProperties props, MockEnvironment ambiente) {
    StaticListableBeanFactory fabrica = new StaticListableBeanFactory();
    fabrica.addBean("mailSender", new JavaMailSenderImpl());
    criar(props, ambiente, fabrica);
  }

  private static void criar(RedefinicaoSenhaProperties props, MockEnvironment ambiente,
      StaticListableBeanFactory fabrica) {
    EmailConfig config = new EmailConfig();
    ObjectProvider<JavaMailSender> cliente = fabrica.getBeanProvider(JavaMailSender.class);
    config.envioEmail(props, DOMINIO, cliente, config.envioEmailSmtp(cliente, props, DOMINIO,
        new TetoEnvioEmail(DOMINIO, Clock.systemUTC())), ambiente);
  }

  @Test
  @DisplayName("LOG so em dev/test: homolog, prod, outro nome ou nenhum profile impedem a subida")
  void logSoEmDev() {
    assertThatCode(() -> criar(props(ModoEmail.LOG, "http://localhost"), perfis("dev")))
        .doesNotThrowAnyException();
    for (String[] perfis : new String[][] {{"homolog"}, {"prod"}, {"producao"}, {}}) {
      assertThatThrownBy(() -> criar(props(ModoEmail.LOG, "https://app"), perfis(perfis)))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  @DisplayName("prod exige link HTTPS mesmo desligado; SMTP sem spring.mail.host impede a subida")
  void prodHttpsESmtp() {
    assertThatThrownBy(() -> criar(props(ModoEmail.DESLIGADO, "http://app"), perfis("prod")))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("HTTPS");
    assertThatCode(() -> criar(props(ModoEmail.DESLIGADO, "https://app"), perfis("prod")))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> criar(props(ModoEmail.SMTP, "https://app"), perfis("prod")))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("spring.mail.host");
  }

  @Test
  @DisplayName("homolog: link http so impede a subida com SMTP (desligado, a VPS sobe sem as URLs)")
  void homologHttpSoComSmtp() {
    assertThatCode(() -> criar(props(ModoEmail.DESLIGADO, "http://localhost:4200/x"),
        perfis("homolog"))).doesNotThrowAnyException();
    assertThatThrownBy(() -> criarComCliente(props(ModoEmail.SMTP, "http://app/redefinir-senha"),
        smtpCompleto("homolog")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("REDEFINICAO_URL_FRONTEND");
    assertThatCode(() -> criarComCliente(props(ModoEmail.SMTP, "https://app/redefinir-senha"),
        smtpCompleto("homolog"))).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("SMTP com host vazio (o que o compose entrega) impede a subida mesmo com cliente")
  void smtpComHostVazio() {
    MockEnvironment ambiente = smtpCompleto("homolog").withProperty("spring.mail.host", "");
    assertThatThrownBy(() -> criarComCliente(props(ModoEmail.SMTP, "https://app"), ambiente))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("spring.mail.host");
  }

  @Test
  @DisplayName("SMTP com remetente fora do dominio verificado impede a subida")
  void smtpComRemetenteForaDoDominio() {
    assertThatThrownBy(() -> criarComCliente(
        props(ModoEmail.SMTP, "https://app", "AgroConIA <nao-responda@agroconia.local>"),
        smtpCompleto("homolog")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("dominio verificado");
  }
}
