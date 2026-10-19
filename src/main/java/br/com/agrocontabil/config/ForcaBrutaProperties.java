package br.com.agrocontabil.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Proteção contra adivinhação de senha (issue #208), em {@code app.seguranca.forca-bruta}.
 *
 * <p>Duas barreiras complementares: o bloqueio por conta para quem tenta muitas senhas contra um
 * e-mail; o limite por IP para quem tenta poucas senhas contra muitos e-mails (o bloqueio por conta
 * nunca dispararia) e para quem usaria o bloqueio para travar contas alheias em massa.
 *
 * @param maxFalhas         senhas erradas seguidas que travam a conta
 * @param bloqueio          por quanto tempo a conta fica travada
 * @param loginPorMinuto    tentativas de login por IP por minuto
 * @param cadastroPorMinuto cadastros por IP por minuto
 * @param refreshPorMinuto  renovações de sessão por IP por minuto
 * @param senhaPorMinuto    pedidos de "esqueci a senha" e redefinições por IP por minuto (#209)
 * @param maxIps            IPs acompanhados ao mesmo tempo: teto de memória do limitador
 */
@ConfigurationProperties(prefix = "app.seguranca.forca-bruta")
public record ForcaBrutaProperties(
    @DefaultValue("5") int maxFalhas,
    @DefaultValue("PT15M") Duration bloqueio,
    @DefaultValue("10") int loginPorMinuto,
    @DefaultValue("5") int cadastroPorMinuto,
    @DefaultValue("30") int refreshPorMinuto,
    @DefaultValue("5") int senhaPorMinuto,
    @DefaultValue("10000") int maxIps) {

  /** Valores que desligariam a proteção em silêncio impedem a subida. */
  public ForcaBrutaProperties {
    if (maxFalhas < 1 || bloqueio.isNegative() || bloqueio.isZero() || loginPorMinuto < 1
        || cadastroPorMinuto < 1 || refreshPorMinuto < 1 || senhaPorMinuto < 1 || maxIps < 1) {
      throw new IllegalStateException(
          "app.seguranca.forca-bruta: limites e duracao do bloqueio precisam ser positivos.");
    }
  }
}
