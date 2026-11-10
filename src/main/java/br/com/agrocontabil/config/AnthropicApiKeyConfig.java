package br.com.agrocontabil.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Com a Ceres no provedor Anthropic ({@code spring.ai.model.chat=anthropic}), a chave de API é
 * exigida na subida.
 *
 * <p>O starter da Anthropic, ao contrário do da OpenAI, sobe com a chave vazia: a aplicação fica
 * saudável, cada pergunta faz uma chamada que volta 401 e a Ceres responde tudo em modo fallback,
 * sem sinal no health. Como o 401 é recusa da requisição, nem o disjuntor abre
 * ({@code ModeloLinguagem.abreDisjuntor}). Recusar a subida deixa o erro de configuração à vista,
 * e o deploy volta sozinho para a versão anterior ({@code deploy/deploy.sh}).
 *
 * <p>Fora do provedor Anthropic esta configuração não existe.
 */
@Configuration
@ConditionalOnProperty(name = "spring.ai.model.chat", havingValue = "anthropic")
public class AnthropicApiKeyConfig {

  /** Mensagem da recusa; nunca traz a chave. */
  static final String MENSAGEM =
      "ANTHROPIC_API_KEY ausente: com AI_CHAT_PROVIDER=anthropic a chave é obrigatória";

  /**
   * Recusa a subida com a chave ausente ou em branco.
   *
   * @param chave valor de {@code spring.ai.anthropic.api-key}
   * @return conferência executada na inicialização do bean
   */
  @Bean
  InitializingBean chaveAnthropicObrigatoria(
      @Value("${spring.ai.anthropic.api-key:}") String chave) {
    return () -> {
      if (chave == null || chave.isBlank()) {
        throw new IllegalStateException(MENSAGEM);
      }
    };
  }
}
