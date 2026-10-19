package br.com.agrocontabil.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.agrocontabil.config.RedefinicaoSenhaProperties.ModoEmail;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("EmailConfig — guardas do envio do link de redefinicao (#209)")
class EmailConfigTest {

  private static RedefinicaoSenhaProperties props(ModoEmail modo, String url) {
    return new RedefinicaoSenhaProperties(Duration.ofMinutes(30), url, modo, "x@x.com");
  }

  private static MockEnvironment perfis(String... perfis) {
    MockEnvironment ambiente = new MockEnvironment();
    ambiente.setActiveProfiles(perfis);
    return ambiente;
  }

  private static void criar(RedefinicaoSenhaProperties props, MockEnvironment ambiente) {
    new EmailConfig().envioEmail(props,
        new StaticListableBeanFactory().getBeanProvider(JavaMailSender.class), ambiente);
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
  @DisplayName("prod exige link HTTPS; SMTP sem spring.mail.host impede a subida")
  void prodHttpsESmtp() {
    assertThatThrownBy(() -> criar(props(ModoEmail.DESLIGADO, "http://app"), perfis("prod")))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("HTTPS");
    assertThatCode(() -> criar(props(ModoEmail.DESLIGADO, "https://app"), perfis("prod")))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> criar(props(ModoEmail.SMTP, "https://app"), perfis("prod")))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("spring.mail.host");
  }
}
