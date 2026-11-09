package br.com.agrocontabil.config;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.service.EnvioConviteCarteira;
import br.com.agrocontabil.service.EnvioConviteEquipe;
import br.com.agrocontabil.service.EnvioEmail;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Subida em {@code homolog} com o cliente SMTP de verdade do Spring Boot (issue #460): o que o
 * {@code deploy/compose.yaml} entrega ({@code SPRING_MAIL_HOST=""}) e os três casos que impedem a
 * subida com o envio ligado.
 */
@DisplayName("Subida do e-mail em homolog com o autoconfigure do Boot (#460)")
class EmailSubidaContextoTest {

  @Configuration
  @EnableConfigurationProperties(RedefinicaoSenhaProperties.class)
  static class Propriedades {
  }

  private static final String[] SMTP_COMPLETO = {
      "app.seguranca.redefinicao.modo-email=SMTP",
      "app.seguranca.redefinicao.remetente=AgroConIA <nao-responda@agroconia.com.br>",
      "app.seguranca.redefinicao.url-frontend=https://www.agroconia.com.br/redefinir-senha",
      "app.carteira.convite.url-frontend=https://www.agroconia.com.br/escritorio",
      "app.escritorio.convite.url-frontend=https://www.agroconia.com.br/equipe/convite",
      "spring.mail.host=smtp.provedor.test",
      "spring.mail.username=usuario",
      "spring.mail.password=senha",
      "spring.mail.properties.mail.smtp.auth=true"};

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class))
      .withUserConfiguration(Propriedades.class, EmailConfig.class, CarteiraConfig.class)
      .withInitializer(contexto -> contexto.getEnvironment().setActiveProfiles("homolog"));

  @Test
  @DisplayName("como o compose: host vazio e envio desligado sobem, e o Boot cria o cliente SMTP")
  void composeComEnvioDesligado() {
    runner.withPropertyValues(
            "app.seguranca.redefinicao.modo-email=DESLIGADO",
            "spring.mail.host=",
            "spring.mail.username=",
            "spring.mail.password=")
        .run(contexto -> {
          assertThat(contexto).hasNotFailed();
          // E por isso que o health do SMTP fica desligado no application.yml: com o cliente
          // criado, o MailHealthIndicator testaria localhost:25 e poria o health em DOWN.
          assertThat(contexto).hasSingleBean(JavaMailSender.class);
          assertThat(contexto).hasSingleBean(EnvioEmail.class);
        });
  }

  @Test
  @DisplayName("SMTP com tudo preenchido e links https sobe com as tres portas")
  void smtpCompleto() {
    runner.withPropertyValues(SMTP_COMPLETO).run(contexto -> {
      assertThat(contexto).hasNotFailed();
      assertThat(contexto).hasSingleBean(EnvioEmail.class);
      assertThat(contexto).hasSingleBean(EnvioConviteCarteira.class);
      assertThat(contexto).hasSingleBean(EnvioConviteEquipe.class);
    });
  }

  @Test
  @DisplayName("SMTP com o host vazio do compose impede a subida")
  void smtpComHostVazio() {
    runner.withPropertyValues(SMTP_COMPLETO).withPropertyValues("spring.mail.host=")
        .run(contexto -> assertThat(contexto).getFailure()
            .rootCause().hasMessageContaining("SPRING_MAIL_HOST"));
  }

  @Test
  @DisplayName("SMTP com remetente em agroconia.local impede a subida")
  void smtpComRemetenteForaDoDominio() {
    runner.withPropertyValues(SMTP_COMPLETO)
        .withPropertyValues(
            "app.seguranca.redefinicao.remetente=AgroConIA <nao-responda@agroconia.local>")
        .run(contexto -> assertThat(contexto).getFailure()
            .rootCause().hasMessageContaining("dominio verificado"));
  }

  @Test
  @DisplayName("SMTP com o link de um convite em http impede a subida")
  void smtpComConviteHttp() {
    runner.withPropertyValues(SMTP_COMPLETO)
        .withPropertyValues("app.escritorio.convite.url-frontend=http://localhost:4200/equipe/convite")
        .run(contexto -> assertThat(contexto).getFailure()
            .rootCause().hasMessageContaining("ESCRITORIO_CONVITE_URL_FRONTEND"));
    runner.withPropertyValues(SMTP_COMPLETO)
        .withPropertyValues("app.carteira.convite.url-frontend=http://localhost:4200/escritorio")
        .run(contexto -> assertThat(contexto).getFailure()
            .rootCause().hasMessageContaining("CARTEIRA_CONVITE_URL_FRONTEND"));
  }
}
