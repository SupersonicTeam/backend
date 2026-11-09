package br.com.agrocontabil.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.StringUtils;

/**
 * E-mail transacional (issue #460), em {@code app.email}.
 *
 * <p>O modo de envio e o remetente continuam em {@link RedefinicaoSenhaProperties} até a banca;
 * aqui ficam só as chaves novas.
 *
 * @param dominioVerificado domínio verificado no provedor (SPF e DKIM). Com o modo {@code SMTP}, o
 *                          remetente tem de ser exatamente deste domínio
 */
@ConfigurationProperties(prefix = "app.email")
public record EmailProperties(@DefaultValue("agroconia.com.br") String dominioVerificado) {

  /** Domínio vazio impede a subida: o remetente nunca seria aceito. */
  public EmailProperties {
    if (!StringUtils.hasText(dominioVerificado)) {
      throw new IllegalStateException("app.email.dominio-verificado nao pode ser vazio.");
    }
  }
}
