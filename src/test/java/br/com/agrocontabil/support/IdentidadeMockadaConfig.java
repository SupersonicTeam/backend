package br.com.agrocontabil.support;

import br.com.agrocontabil.config.MockIdentidadeFilter;
import br.com.agrocontabil.repository.ContadorRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Registra o {@link MockIdentidadeFilter} em testes com servidor real ({@code RANDOM_PORT}).
 *
 * <p>Fora de {@code dev}/{@code homolog} o filtro não existe; com o bean no contexto, o
 * {@code SecurityConfig} o põe na cadeia na mesma posição desses profiles, e a identidade vem do
 * header {@code X-Usuario-Id}. Importar com {@code @Import}.
 */
@TestConfiguration
public class IdentidadeMockadaConfig {

  @Bean
  MockIdentidadeFilter mockIdentidadeFilter(
      ProdutorRepository produtores, ContadorRepository contadores) {
    return new MockIdentidadeFilter(produtores, contadores);
  }
}
