package br.com.agrocontabil.config;

import java.time.Duration;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Propriedades da Ceres, a assistente fiscal (issue #141).
 *
 * <p>Prefix: {@code app.assistente}. A escolha do provedor e do modelo fica nas propriedades do
 * próprio Spring AI ({@code spring.ai.*}); aqui vivem só as decisões do AgroContabil.
 *
 * <pre>
 * app:
 *   assistente:
 *     saude:
 *       timeout: 2s     # timeout do health do Ollama
 *       cache: 10s      # por quanto tempo o último resultado do health é reaproveitado
 * </pre>
 *
 * <p>Registrada via {@link org.springframework.boot.context.properties.EnableConfigurationProperties}
 * em {@link AssistenteConfig}.
 */
@ConfigurationProperties(prefix = "app.assistente")
public class AssistenteProperties implements InitializingBean {

  /** Health indicator do Ollama. */
  private Saude saude = new Saude();

  /**
   * Recusa valores fora de faixa na subida, em vez de descobrir o problema no primeiro health.
   *
   * @throws IllegalStateException se algum valor for nulo, zero ou negativo
   */
  @Override
  public void afterPropertiesSet() {
    exigirPositivo(saude.getTimeout(), "app.assistente.saude.timeout");
    exigirPositivo(saude.getCache(), "app.assistente.saude.cache");
  }

  private static void exigirPositivo(Duration valor, String nome) {
    if (valor == null || valor.isNegative() || valor.isZero()) {
      throw new IllegalStateException(nome + " deve ser maior que zero, recebido: " + valor);
    }
  }

  public Saude getSaude() {
    return saude;
  }

  public void setSaude(Saude saude) {
    this.saude = saude;
  }

  /** Configuração do health indicator do Ollama. */
  public static class Saude {

    /**
     * Timeout de conexão e de leitura da consulta ao Ollama. Curto de propósito: o health não pode
     * herdar o read-timeout da geração ({@code spring.http.client.read-timeout}).
     */
    private Duration timeout = Duration.ofSeconds(2);

    /**
     * Janela em que o último resultado é reaproveitado. O {@code /actuator/health} é público:
     * sem o cache, cada visitante dispararia uma requisição ao Ollama.
     */
    private Duration cache = Duration.ofSeconds(10);

    public Duration getTimeout() {
      return timeout;
    }

    public void setTimeout(Duration timeout) {
      this.timeout = timeout;
    }

    public Duration getCache() {
      return cache;
    }

    public void setCache(Duration cache) {
      this.cache = cache;
    }
  }
}
