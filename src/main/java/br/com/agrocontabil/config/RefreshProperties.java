package br.com.agrocontabil.config;

import java.time.Duration;
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
}
