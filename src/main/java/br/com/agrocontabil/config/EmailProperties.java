package br.com.agrocontabil.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.StringUtils;

/**
 * E-mail transacional (issue #460), em {@code app.email}.
 *
 * <p>O modo de envio e o remetente continuam em {@link RedefinicaoSenhaProperties} até a banca;
 * aqui ficam só as chaves novas.
 *
 * @param dominioVerificado      domínio verificado no provedor (SPF e DKIM). Com o modo
 *                               {@code SMTP}, o remetente tem de ser exatamente deste domínio
 * @param replyTo                endereço das respostas ({@code Reply-To}); o remetente não recebe
 *                               e-mail. Vazio = sem {@code Reply-To}
 * @param tetoPorHora            e-mails enviados por SMTP em qualquer janela de 1 hora; acima, o
 *                               e-mail é descartado. {@code 0} = sem teto
 * @param tetoPorDia             o mesmo em qualquer janela de 24 horas. {@code 0} = sem teto. Fica
 *                               abaixo do limite do provedor (Resend Free: 100 por dia)
 * @param linksRedefinicaoPorDia links de redefinição de senha seguidos por conta; depois, um a cada
 *                               24 horas divididas por este número (balde de fichas do
 *                               {@code LimitadorTaxa})
 * @param dominiosReservados     destinos que nunca recebem e-mail: o domínio igual ou subdomínio
 *                               da entrada; entrada com ponto no início vale como sufixo
 *                               ({@code .test}). As contas do seed são {@code @example.com}
 */
@ConfigurationProperties(prefix = "app.email")
public record EmailProperties(
    @DefaultValue("agroconia.com.br") String dominioVerificado,
    @DefaultValue("contato@gwnext.com.br") String replyTo,
    @DefaultValue("0") int tetoPorHora,
    @DefaultValue("80") int tetoPorDia,
    @DefaultValue("5") int linksRedefinicaoPorDia,
    @DefaultValue({"example.com", "example.net", "example.org", ".example", ".test", ".invalid",
        ".local", ".localhost"}) List<String> dominiosReservados) {

  /** Valores fora da faixa impedem a subida. */
  public EmailProperties {
    if (!StringUtils.hasText(dominioVerificado)) {
      throw new IllegalStateException("app.email.dominio-verificado nao pode ser vazio.");
    }
    if (tetoPorHora < 0 || tetoPorDia < 0) {
      throw new IllegalStateException(
          "app.email.teto-por-hora e app.email.teto-por-dia nao podem ser negativos (0 = sem teto).");
    }
    if (linksRedefinicaoPorDia < 1) {
      throw new IllegalStateException(
          "app.email.links-redefinicao-por-dia precisa ser positivo.");
    }
    dominiosReservados = dominiosReservados == null ? List.of() : List.copyOf(dominiosReservados);
  }
}
