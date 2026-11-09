package br.com.agrocontabil.config;

import br.com.agrocontabil.config.RedefinicaoSenhaProperties.ModoEmail;
import br.com.agrocontabil.service.EnvioEmail;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Escolhe como o link de redefinição de senha chega ao titular (issue #209).
 *
 * <ul>
 *   <li>{@code SMTP}: envio real pelo {@link JavaMailSender} do Spring Boot ({@code spring.mail.*});
 *       sem host, sem usuário e senha, com remetente fora do domínio verificado ou, fora de
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

  private static final String ASSUNTO = "Redefinicao de senha - AgroConIA";

  /** Profiles em que o modo LOG é aceito: a máquina do desenvolvedor e os testes. */
  static final List<String> PERFIS_COM_LOG = ConferenciaEmail.PERFIS_LOCAIS;

  /**
   * Implementação da porta de e-mail conforme o modo configurado.
   *
   * @param propriedades configuração da redefinição
   * @param email        domínio verificado do remetente
   * @param mailSender   cliente SMTP do Boot, se configurado
   * @param ambiente     para recusar o modo LOG em produção
   * @return a porta de envio
   */
  @Bean
  public EnvioEmail envioEmail(
      RedefinicaoSenhaProperties propriedades,
      EmailProperties email,
      ObjectProvider<JavaMailSender> mailSender,
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
      case SMTP -> smtp(ConferenciaEmail.clienteSmtp(ambiente, mailSender,
          propriedades.remetente(), email.dominioVerificado()), propriedades.remetente());
      case LOG -> (destinatario, link, validade) ->
          log.warn("[DEV] E-mail de redefinicao de senha NAO enviado (modo LOG). Link: {}", link);
      case DESLIGADO -> {
        log.warn("Envio de e-mail desligado: a recuperacao de senha nao entrega o link.");
        yield (destinatario, link, validade) -> log.info("Pedido de redefinicao sem envio "
            + "(modo-email=DESLIGADO).");
      }
    };
  }

  private static EnvioEmail smtp(JavaMailSender mailSender, String remetente) {
    return (destinatario, link, validade) -> {
      SimpleMailMessage mensagem = new SimpleMailMessage();
      mensagem.setFrom(remetente);
      mensagem.setTo(destinatario);
      mensagem.setSubject(ASSUNTO);
      mensagem.setText("""
          Recebemos um pedido para redefinir a senha da sua conta no AgroConIA.

          Para escolher uma senha nova, abra o link abaixo em ate %d minutos:

          %s

          O link vale uma vez so. Se voce nao pediu a redefinicao, ignore este e-mail: sua senha
          continua a mesma.
          """.formatted(validade.toMinutes(), link));
      mailSender.send(mensagem);
    };
  }
}
