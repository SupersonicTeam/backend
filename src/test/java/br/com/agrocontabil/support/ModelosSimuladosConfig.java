package br.com.agrocontabil.support;

import br.com.agrocontabil.assistente.llm.DisponibilidadeModelo;
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

  /**
   * O disjuntor do modo fallback (#146) é singleton e o contexto é reaproveitado entre classes de
   * teste: sem fechá-lo no {@link ChatModelSimulado#reiniciar()}, um teste que derruba o modelo
   * mandaria ao fallback as perguntas dos testes seguintes.
   */
  @Bean
  ChatModelSimulado chatModelSimulado(DisponibilidadeModelo disponibilidade) {
    ChatModelSimulado modelo = new ChatModelSimulado();
    modelo.aoReiniciar(disponibilidade::registrarSucesso);
    return modelo;
  }

  @Bean
  EmbeddingModelSimulado embeddingModelSimulado() {
    return new EmbeddingModelSimulado();
  }
}
