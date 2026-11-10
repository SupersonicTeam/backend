package br.com.agrocontabil.config;

import br.com.agrocontabil.config.RedefinicaoSenhaProperties.ModoEmail;
import br.com.agrocontabil.service.EnvioEmail;
import br.com.agrocontabil.service.MensagensEmail;
import br.com.agrocontabil.service.TetoEnvioEmail;
import br.com.agrocontabil.service.TipoEmail;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Escolhe como o link de redefinição de senha chega ao titular (issue #209).
 *
 * <ul>
 *   <li>{@code SMTP}: envio real pelo {@link JavaMailSender} do Spring Boot ({@code spring.mail.*}),
 *       pelo {@link EnvioEmailSmtp} (destino reservado, teto, {@code Reply-To}); sem host, sem
 *       usuário e senha, com remetente fora do domínio verificado ou, fora de
 *       {@code dev}/{@code test}, com link {@code http}, a aplicação não sobe
 *       ({@link ConferenciaEmail}, issue #460);
 *   <li>{@code LOG}: o link vai para o log em WARN. É um segredo em log, aceitável só na máquina do
 *       desenvolvedor — <strong>a aplicação só sobe com {@code LOG} nos profiles {@code dev} e
 *       {@code test}</strong>;
 *   <li>{@code DESLIGADO} (padrão): nada é enviado. A recuperação de senha fica indisponível, e a
 *       resposta continua 202 (não pode diferir de quando o e-mail não tem conta).
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(EmailProperties.class)
public class EmailConfig {

  private static final Logger log = LoggerFactory.getLogger(EmailConfig.class);

  /** Profiles em que o modo LOG é aceito: a máquina do desenvolvedor e os testes. */
  static final List<String> PERFIS_COM_LOG = ConferenciaEmail.PERFIS_LOCAIS;

  /**
   * Envio pelo SMTP, comum às três portas (o teto de envios é um só). Sem efeito fora do modo
   * {@code SMTP}: o cliente SMTP só é pedido no envio.
   *
   * @param mailSender   cliente SMTP do Boot, se configurado
   * @param propriedades remetente
   * @param email        {@code Reply-To} e domínios reservados
   * @param teto         teto de envios
   * @return o envio SMTP
   */
  @Bean
  EnvioEmailSmtp envioEmailSmtp(ObjectProvider<JavaMailSender> mailSender,
      RedefinicaoSenhaProperties propriedades, EmailProperties email, TetoEnvioEmail teto) {
    return new EnvioEmailSmtp(mailSender, propriedades.remetente(), email, teto);
  }

  /**
   * Implementação da porta de e-mail conforme o modo configurado.
   *
   * @param propriedades configuração da redefinição
   * @param email        domínio verificado do remetente e {@code Reply-To}
   * @param mailSender   cliente SMTP do Boot, se configurado
   * @param smtp         envio pelo SMTP
   * @param ambiente     para recusar o modo LOG em produção
   * @return a porta de envio
   */
  @Bean
  public EnvioEmail envioEmail(
      RedefinicaoSenhaProperties propriedades,
      EmailProperties email,
      ObjectProvider<JavaMailSender> mailSender,
      EnvioEmailSmtp smtp,
      Environment ambiente) {
    ModoEmail modo = propriedades.modoEmail();
    List<String> perfis = Arrays.asList(ambiente.getActiveProfiles());
    // Lista de permissao, nao de bloqueio: um profile com outro nome (homolog, producao, nenhum)
    // tambem nao pode por links vivos de redefinicao no log (achado do review da #209).
    if (modo == ModoEmail.LOG && perfis.stream().noneMatch(PERFIS_COM_LOG::contains)) {
      throw new IllegalStateException("app.seguranca.redefinicao.modo-email=LOG poe o link de "
          + "redefinicao (um segredo) no log: permitido so nos profiles " + PERFIS_COM_LOG
          + ". Use SMTP.");
    }
    ConferenciaEmail.exigirHttps("app.seguranca.redefinicao.url-frontend "
        + "(REDEFINICAO_URL_FRONTEND)", propriedades.urlFrontend(), modo == ModoEmail.SMTP,
        ambiente);
    return switch (modo) {
      case SMTP -> {
        ConferenciaEmail.conferirSmtp(ambiente, mailSender, propriedades.remetente(), email);
        yield (destinatario, link, validade) -> smtp.enviar(TipoEmail.REDEFINICAO_SENHA,
            destinatario, MensagensEmail.redefinicaoSenha(link, validade, email.replyTo()));
      }
      case LOG -> (destinatario, link, validade) ->
          log.warn("[DEV] E-mail de redefinicao de senha NAO enviado (modo LOG). Link: {}", link);
      case DESLIGADO -> {
        log.warn("Envio de e-mail desligado: a recuperacao de senha nao entrega o link.");
        yield (destinatario, link, validade) -> log.info("Pedido de redefinicao sem envio "
            + "(modo-email=DESLIGADO).");
      }
    };
  }
}
