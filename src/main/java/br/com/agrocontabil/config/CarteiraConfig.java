package br.com.agrocontabil.config;

import br.com.agrocontabil.config.RedefinicaoSenhaProperties.ModoEmail;
import br.com.agrocontabil.service.EnvioConviteCarteira;
import br.com.agrocontabil.service.EnvioConviteEquipe;
import br.com.agrocontabil.service.MensagensEmail;
import br.com.agrocontabil.service.TipoEmail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Carteira do escritório (issue #177): propriedades do convite e o envio do e-mail.
 *
 * <p>O modo de envio é o mesmo da recuperação de senha ({@code app.seguranca.redefinicao
 * .modo-email}, validado no {@link EmailConfig}): um servidor de e-mail só para o sistema. No modo
 * {@code SMTP} os dois convites saem pelo {@link EnvioEmailSmtp}, com os textos do
 * {@link MensagensEmail} (#460). No modo {@code LOG} vai ao log só o fato de o convite existir.
 */
@Configuration
@EnableConfigurationProperties({ConviteCarteiraProperties.class, ConviteEquipeProperties.class})
public class CarteiraConfig {

  private static final Logger log = LoggerFactory.getLogger(CarteiraConfig.class);

  /**
   * Implementação do envio do convite conforme o modo de e-mail.
   *
   * @param redefinicao configuração de e-mail (modo e remetente)
   * @param email       domínio verificado do remetente e {@code Reply-To}
   * @param convite     link do convite
   * @param mailSender  cliente SMTP do Boot, se configurado
   * @param smtp        envio pelo SMTP
   * @param ambiente    profiles e {@code spring.mail.*}, para as conferências de subida (#460)
   * @return a porta de envio
   */
  @Bean
  public EnvioConviteCarteira envioConviteCarteira(RedefinicaoSenhaProperties redefinicao,
      EmailProperties email, ConviteCarteiraProperties convite,
      ObjectProvider<JavaMailSender> mailSender, EnvioEmailSmtp smtp, Environment ambiente) {
    ConferenciaEmail.exigirHttps("app.carteira.convite.url-frontend "
        + "(CARTEIRA_CONVITE_URL_FRONTEND)", convite.urlFrontend(),
        redefinicao.modoEmail() == ModoEmail.SMTP, ambiente);
    return switch (redefinicao.modoEmail()) {
      case SMTP -> {
        ConferenciaEmail.conferirSmtp(ambiente, mailSender, redefinicao.remetente(), email);
        yield (destinatario, escritorio, link, expiraEm) -> smtp.enviar(
            TipoEmail.CONVITE_CARTEIRA, destinatario,
            MensagensEmail.conviteCarteira(escritorio, link, expiraEm, email.replyTo()));
      }
      case LOG -> (destinatario, escritorio, link, expiraEm) ->
          log.warn("[DEV] E-mail de convite da carteira NAO enviado (modo LOG).");
      case DESLIGADO -> (destinatario, escritorio, link, expiraEm) ->
          log.info("Convite da carteira sem envio de e-mail (modo-email=DESLIGADO).");
    };
  }

  /**
   * Envio do convite de equipe (issue #228). No modo {@code LOG} o link não vai ao log: leva o
   * token do convite.
   *
   * @param redefinicao configuração de e-mail (modo e remetente)
   * @param email       domínio verificado do remetente e {@code Reply-To}
   * @param convite     link do convite
   * @param mailSender  cliente SMTP do Boot, se configurado
   * @param smtp        envio pelo SMTP
   * @param ambiente    profiles e {@code spring.mail.*}, para as conferências de subida (#460)
   * @return a porta de envio
   */
  @Bean
  public EnvioConviteEquipe envioConviteEquipe(RedefinicaoSenhaProperties redefinicao,
      EmailProperties email, ConviteEquipeProperties convite,
      ObjectProvider<JavaMailSender> mailSender, EnvioEmailSmtp smtp, Environment ambiente) {
    ConferenciaEmail.exigirHttps("app.escritorio.convite.url-frontend "
        + "(ESCRITORIO_CONVITE_URL_FRONTEND)", convite.urlFrontend(),
        redefinicao.modoEmail() == ModoEmail.SMTP, ambiente);
    return switch (redefinicao.modoEmail()) {
      case SMTP -> {
        ConferenciaEmail.conferirSmtp(ambiente, mailSender, redefinicao.remetente(), email);
        yield (destinatario, escritorio, link, expiraEm) -> smtp.enviar(
            TipoEmail.CONVITE_EQUIPE, destinatario,
            MensagensEmail.conviteEquipe(escritorio, link, expiraEm, email.replyTo()));
      }
      case LOG -> (destinatario, escritorio, link, expiraEm) ->
          log.warn("[DEV] E-mail de convite de equipe NAO enviado (modo LOG).");
      case DESLIGADO -> (destinatario, escritorio, link, expiraEm) ->
          log.info("Convite de equipe sem envio de e-mail (modo-email=DESLIGADO).");
    };
  }
}
