package br.com.agrocontabil.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Refresh token e o cookie que o leva (issue #207), em {@code app.seguranca.refresh}.
 *
 * @param validade        vida de cada refresh token; cada renovação emite um novo com esta validade
 * @param vidaMaxima      vida máxima da sessão (família): a renovação nunca passa disso, e depois
 *                        dela é preciso entrar de novo com a senha
 * @param cookieNome      nome do cookie
 * @param cookieSeguro    atributo {@code Secure}. Desligar só em dev sobre HTTP
 * @param cookieSameSite  atributo {@code SameSite}: {@code Strict} impede o navegador de mandar o
 *                        cookie em requisição iniciada por outro site (CSRF)
 * @param cookieCaminho   atributo {@code Path}: o cookie só vai para os endpoints de autenticação
 * @param retencao        por quanto tempo uma sessão vencida fica no banco antes da limpeza (dá
 *                        tempo de investigar um reuso detectado)
 */
@ConfigurationProperties(prefix = "app.seguranca.refresh")
public record RefreshProperties(
    @DefaultValue("P7D") Duration validade,
    @DefaultValue("P30D") Duration vidaMaxima,
    @DefaultValue("agro_refresh") String cookieNome,
    @DefaultValue("true") boolean cookieSeguro,
    @DefaultValue("Strict") String cookieSameSite,
    @DefaultValue("/api/auth") String cookieCaminho,
    @DefaultValue("P7D") Duration retencao) {

  /**
   * Recusa na subida a configuração que o navegador descartaria em silêncio ou que reabriria o
   * CSRF de {@code /refresh} e {@code /logout}.
   *
   * <ul>
   *   <li>{@code SameSite} só {@code Strict}, {@code Lax} ou {@code None};
   *   <li>{@code None} exige {@code Secure} — sem ele o navegador nem grava o cookie;
   *   <li>validade e vida máxima positivas, e a validade não maior que a vida máxima.
   * </ul>
   */
  public RefreshProperties {
    if (!List.of("Strict", "Lax", "None").contains(cookieSameSite)) {
      throw new IllegalStateException(
          "app.seguranca.refresh.cookie-same-site invalido: use Strict, Lax ou None.");
    }
    if ("None".equals(cookieSameSite) && !cookieSeguro) {
      throw new IllegalStateException(
          "Cookie de refresh com SameSite=None exige cookie-seguro=true (o navegador o descarta).");
    }
    if (validade.isNegative() || validade.isZero() || vidaMaxima.compareTo(validade) < 0) {
      throw new IllegalStateException(
          "app.seguranca.refresh: validade deve ser positiva e nao maior que a vida-maxima.");
    }
  }
}
