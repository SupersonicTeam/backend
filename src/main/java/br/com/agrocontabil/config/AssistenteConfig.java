package br.com.agrocontabil.config;

import br.com.agrocontabil.assistente.GlossarioProdutor;
import br.com.agrocontabil.assistente.llm.InterceptorRaciocinioAnthropic;
import br.com.agrocontabil.assistente.llm.PoliticaRetryModelo;
import br.com.agrocontabil.assistente.llm.ThinkOptionConverter;
import br.com.agrocontabil.assistente.llm.TratadorErroProvedor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryListener;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.ResponseErrorHandler;

/**
 * Configuração da Ceres, a assistente fiscal (issue #141).
 *
 * <p>Registra {@link AssistenteProperties} (via {@link EnableConfigurationProperties}, padrão do
 * projeto), o conversor do {@code think-option} do Ollama, o {@link RetryTemplate} das chamadas ao
 * modelo e o tratador das respostas de erro do provedor.
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
   * Raciocínio do provedor numa chamada só (#269, juiz da fidelidade da lei, opção A): o
   * {@link InterceptorRaciocinioAnthropic} entra no {@code RestClient.Builder} do Boot, que o Spring
   * AI da Anthropic usa. Ele só age na requisição marcada pelo juiz e ao host da API da Anthropic;
   * as outras passam intactas.
   */
  @Bean
  RestClientCustomizer interceptorRaciocinioAnthropic(
      @Value("${spring.ai.anthropic.base-url:https://api.anthropic.com}") String urlBase) {
    return builder -> builder.requestInterceptor(new InterceptorRaciocinioAnthropic(urlBase));
  }

  /**
   * Glossário do produtor (#267) como bean: lê o {@code glossario-produtor.yml} na subida, então
   * arquivo ausente ou termo inválido derruba a aplicação na hora (e não na primeira resposta).
   */
  @Bean
  GlossarioProdutor glossarioProdutor() {
    return GlossarioProdutor.padrao();
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

  /**
   * Substitui o tratador de erro HTTP do Spring AI (que recua por {@code @ConditionalOnMissingBean})
   * por um que dá tipo próprio ao HTTP 429 transitório — ver {@link TratadorErroProvedor}. A
   * classificação continua a do Spring AI, por {@code spring.ai.retry.*}: o tratador dele é criado
   * aqui e fica por baixo.
   */
  @Bean
  public ResponseErrorHandler responseErrorHandler(SpringAiRetryProperties propriedades) {
    return new TratadorErroProvedor(
        new SpringAiRetryAutoConfiguration().responseErrorHandler(propriedades));
  }
}
