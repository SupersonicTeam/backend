package br.com.agrocontabil.support;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Modelos simulados do profile {@code test} (issue #141).
 *
 * <p>O {@code application-test.yml} desliga os modelos reais ({@code spring.ai.model.chat} e
 * {@code spring.ai.model.embedding} = {@code none}); esta configuração, achada pelo component scan
 * no classpath de teste, registra os substitutos. Assim a CI roda sem Ollama e sem chave de API.
 */
@Configuration
@Profile("test")
public class ModelosSimuladosConfig {

  @Bean
  ChatModelSimulado chatModelSimulado() {
    return new ChatModelSimulado();
  }

  @Bean
  EmbeddingModelSimulado embeddingModelSimulado() {
    return new EmbeddingModelSimulado();
  }
}
