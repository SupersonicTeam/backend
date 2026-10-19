package br.com.agrocontabil.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Recuperação de senha (issue #209), em {@code app.seguranca.redefinicao}.
 *
 * @param validade    vida do link de redefinição
 * @param urlFrontend página do Angular que recebe o token; o token vai no <em>fragmento</em>
 *                    ({@code #token=}), que o navegador não manda ao servidor nem põe no
 *                    {@code Referer} — não aparece em log de acesso nem vaza para terceiros
 * @param modoEmail   {@code SMTP} (envio real por {@code spring.mail.*}), {@code LOG} (o link vai
 *                    para o log — só dev; proibido em prod) ou {@code DESLIGADO}
 * @param remetente   endereço {@code From} das mensagens
 */
@ConfigurationProperties(prefix = "app.seguranca.redefinicao")
public record RedefinicaoSenhaProperties(
    @DefaultValue("PT30M") Duration validade,
    @DefaultValue("http://localhost:4200/redefinir-senha") String urlFrontend,
    @DefaultValue("DESLIGADO") ModoEmail modoEmail,
    @DefaultValue("nao-responda@agrocontabil.local") String remetente) {

  /** Como o link de redefinição chega ao titular. */
  public enum ModoEmail {
    SMTP,
    LOG,
    DESLIGADO
  }

  /** Validade fora de 1 min a 24 h impede a subida: link eterno é senha alternativa. */
  public RedefinicaoSenhaProperties {
    if (validade.compareTo(Duration.ofMinutes(1)) < 0
        || validade.compareTo(Duration.ofHours(24)) > 0) {
      throw new IllegalStateException(
          "app.seguranca.redefinicao.validade precisa estar entre 1 minuto e 24 horas.");
    }
  }
}
