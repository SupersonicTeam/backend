package br.com.agrocontabil.config;

import br.com.agrocontabil.assistente.llm.PoliticaRetryModelo;
import br.com.agrocontabil.assistente.llm.ThinkOptionConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryProperties;
import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryListener;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.support.RetryTemplate;

/**
 * Configuração da Ceres, a assistente fiscal (issue #141).
 *
 * <p>Registra {@link AssistenteProperties} (via {@link EnableConfigurationProperties}, padrão do
 * projeto), o conversor do {@code think-option} do Ollama e o {@link RetryTemplate} das chamadas ao
 * modelo.
 */
@Configuration
@EnableConfigurationProperties(AssistenteProperties.class)
public class AssistenteConfig {

  private static final Logger log = LoggerFactory.getLogger(AssistenteConfig.class);

  /**
   * Permite escrever {@code think-option: false} no yml. {@code static}: conversores de binding
   * precisam existir antes de qualquer {@code @ConfigurationProperties} ser vinculado.
   */
  @Bean
  @ConfigurationPropertiesBinding
  static ThinkOptionConverter thinkOptionConverter() {
    return new ThinkOptionConverter();
  }

  /**
   * Substitui o {@code RetryTemplate} do Spring AI (que recua por {@code @ConditionalOnMissingBean})
   * para <strong>não</strong> re-tentar read-timeout — ver {@link PoliticaRetryModelo}. Tentativas e
   * backoff continuam vindo de {@code spring.ai.retry.*}.
   *
   * <p>O listener registra só o tipo da falha: o listener padrão do Spring AI loga a exceção
   * inteira, e a mensagem de erro de um provedor por API pode ecoar a requisição.
   *
   * <p>Nenhum outro componente usa {@code RetryTemplate}: o retry da SEFAZ é por
   * {@code @Retryable} ({@code RetryConfig}).
   */
  @Bean
  public RetryTemplate retryTemplate(SpringAiRetryProperties propriedades) {
    ExponentialBackOffPolicy backoff = new ExponentialBackOffPolicy();
    backoff.setInitialInterval(propriedades.getBackoff().getInitialInterval().toMillis());
    backoff.setMultiplier(propriedades.getBackoff().getMultiplier());
    backoff.setMaxInterval(propriedades.getBackoff().getMaxInterval().toMillis());

    RetryTemplate template = new RetryTemplate();
    template.setRetryPolicy(new PoliticaRetryModelo(propriedades.getMaxAttempts()));
    template.setBackOffPolicy(backoff);
    template.registerListener(new RetryListener() {
      @Override
      public <T, E extends Throwable> void onError(
          RetryContext context, RetryCallback<T, E> callback, Throwable falha) {
        log.warn("Falha na chamada ao modelo (tentativa {}): {}",
            context.getRetryCount(), falha.getClass().getName());
      }
    });
    return template;
  }
}
