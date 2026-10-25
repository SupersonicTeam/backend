package br.com.agrocontabil.config;

import br.com.agrocontabil.service.EnvioConviteCarteira;
import br.com.agrocontabil.service.EnvioConviteEquipe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Carteira do escritório (issue #177): propriedades do convite e o envio do e-mail.
 *
 * <p>O modo de envio é o mesmo da recuperação de senha ({@code app.seguranca.redefinicao
 * .modo-email}, validado no {@link EmailConfig}): um servidor de e-mail só para o sistema. No modo
 * {@code LOG} vai ao log só o fato de o convite existir — o e-mail não leva segredo.
 */
@Configuration
@EnableConfigurationProperties({ConviteCarteiraProperties.class, ConviteEquipeProperties.class})
public class CarteiraConfig {

  private static final Logger log = LoggerFactory.getLogger(CarteiraConfig.class);

  private static final String ASSUNTO = "Convite de escritorio de contabilidade - AgroContabil";

  /**
   * Implementação do envio do convite conforme o modo de e-mail.
   *
   * @param redefinicao configuração de e-mail (modo e remetente)
   * @param mailSender  cliente SMTP do Boot, se configurado
   * @return a porta de envio
   */
  @Bean
  public EnvioConviteCarteira envioConviteCarteira(
      RedefinicaoSenhaProperties redefinicao, ObjectProvider<JavaMailSender> mailSender) {
    return switch (redefinicao.modoEmail()) {
      case SMTP -> smtp(mailSender.getIfAvailable(), redefinicao.remetente());
      case LOG -> (destinatario, escritorio, link, validade) ->
          log.warn("[DEV] E-mail de convite da carteira NAO enviado (modo LOG).");
      case DESLIGADO -> (destinatario, escritorio, link, validade) ->
          log.info("Convite da carteira sem envio de e-mail (modo-email=DESLIGADO).");
    };
  }

  /**
   * Envio do convite de equipe (issue #228). No modo {@code LOG} o link não vai ao log: leva o
   * token do convite.
   *
   * @param redefinicao configuração de e-mail (modo e remetente)
   * @param mailSender  cliente SMTP do Boot, se configurado
   * @return a porta de envio
   */
  @Bean
  public EnvioConviteEquipe envioConviteEquipe(
      RedefinicaoSenhaProperties redefinicao, ObjectProvider<JavaMailSender> mailSender) {
    return switch (redefinicao.modoEmail()) {
      case SMTP -> {
        JavaMailSender cliente = mailSender.getIfAvailable();
        if (cliente == null) {
          throw new IllegalStateException(
              "app.seguranca.redefinicao.modo-email=SMTP exige spring.mail.host configurado.");
        }
        String remetente = redefinicao.remetente();
        yield (destinatario, escritorio, link, validade) -> {
          SimpleMailMessage mensagem = new SimpleMailMessage();
          mensagem.setFrom(remetente);
          mensagem.setTo(destinatario);
          mensagem.setSubject("Convite para a equipe do escritorio - AgroContabil");
          mensagem.setText("""
              Voce foi convidado para a equipe do escritorio "%s" no AgroContabil.

              Para aceitar, abra o link abaixo em ate %d dias e entre (ou crie sua conta) com
              este e-mail:

              %s

              O link vale uma vez so. Se nao conhece o escritorio, ignore este e-mail.
              """.formatted(escritorio, Math.max(1, validade.toDays()), link));
          cliente.send(mensagem);
        };
      }
      case LOG -> (destinatario, escritorio, link, validade) ->
          log.warn("[DEV] E-mail de convite de equipe NAO enviado (modo LOG).");
      case DESLIGADO -> (destinatario, escritorio, link, validade) ->
          log.info("Convite de equipe sem envio de e-mail (modo-email=DESLIGADO).");
    };
  }

  private static EnvioConviteCarteira smtp(JavaMailSender mailSender, String remetente) {
    if (mailSender == null) {
      throw new IllegalStateException(
          "app.seguranca.redefinicao.modo-email=SMTP exige spring.mail.host configurado.");
    }
    return (destinatario, escritorio, link, validade) -> {
      SimpleMailMessage mensagem = new SimpleMailMessage();
      mensagem.setFrom(remetente);
      mensagem.setTo(destinatario);
      mensagem.setSubject(ASSUNTO);
      mensagem.setText("""
          O escritorio de contabilidade "%s" convidou voce para a carteira de clientes dele no
          AgroContabil.

          Para responder, entre no AgroContabil com este e-mail (ou crie sua conta com ele) e
          abra a pagina de convites em ate %d dias:

          %s

          O escritorio so passa a ver seus dados depois que voce aceitar. Se nao conhece o
          escritorio, recuse ou ignore este e-mail.
          """.formatted(escritorio, validade.toDays(), link));
      mailSender.send(mensagem);
    };
  }
}
