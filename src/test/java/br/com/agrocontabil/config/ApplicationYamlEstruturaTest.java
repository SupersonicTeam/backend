package br.com.agrocontabil.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Trava a estrutura do {@code application.yml} e dos YAMLs de profile (issue #125).
 *
 * <p>O defeito: o bloco {@code server:} foi inserido na coluna 0 no meio do bloco {@code spring:},
 * e tudo o que vinha depois dele virou {@code server.jpa.*}, {@code server.servlet.multipart.*},
 * {@code server.liquibase.*} — chaves que não existem e que o Spring ignora em silêncio. O
 * open-in-view voltou a ligar, o {@code ddl-auto: validate} sumiu e o batch fetch da #81 deixou de
 * valer, sem nenhum erro na subida.
 *
 * <p>Os testes de contexto não pegam isso: o profile {@code test} sobrescreve {@code ddl-auto} e
 * ninguém lê {@code server.jpa}. Daí ler o YAML e exigir cada chave no namespace certo.
 */
class ApplicationYamlEstruturaTest {

  /**
   * Sub-namespaces que só existem dentro de {@code spring.*}. Encontrados como segundo segmento
   * de uma chave que não começa com {@code spring}, denunciam um bloco de topo inserido no meio de
   * {@code spring:} — exatamente o defeito da issue #125. Nomes genéricos que o namespace
   * {@code app} também usa (ex.: {@code app.security}) ficam de fora para não dar falso positivo.
   */
  private static final Set<String> SUBNAMESPACES_DO_SPRING = Set.of(
      "application", "profiles", "data", "datasource", "jpa", "liquibase", "transaction",
      "task", "lifecycle", "servlet", "ai", "http");

  private static Properties propriedades;

  @BeforeAll
  static void carregar() {
    YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
    yaml.setResources(new ClassPathResource("application.yml"));
    propriedades = yaml.getObject();
  }

  @Test
  @DisplayName("open-in-view desligado no namespace spring.jpa")
  void openInViewDesligado() {
    assertThat(propriedades.getProperty("spring.jpa.open-in-view")).isEqualTo("false");
  }

  @Test
  @DisplayName("Hibernate so valida o schema (Liquibase e o dono do DDL)")
  void ddlAutoValidate() {
    assertThat(propriedades.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
  }

  @Test
  @DisplayName("propriedades do Hibernate (batch fetch da #81 e fuso JDBC) em spring.jpa.properties")
  void propriedadesHibernate() {
    assertThat(propriedades.getProperty(
        "spring.jpa.properties.hibernate.default_batch_fetch_size")).isEqualTo("25");
    assertThat(propriedades.getProperty("spring.jpa.properties.hibernate.jdbc.time_zone"))
        .isEqualTo("America/Sao_Paulo");
  }

  @Test
  @DisplayName("limites de upload em spring.servlet.multipart")
  void limitesDeUpload() {
    assertThat(propriedades.getProperty("spring.servlet.multipart.max-file-size"))
        .isEqualTo("2MB");
    assertThat(propriedades.getProperty("spring.servlet.multipart.max-request-size"))
        .isEqualTo("20MB");
  }

  @Test
  @DisplayName("Ceres: provedor por env, embedding fixo local e chave so por env (issue #141)")
  void ceresConfiguradaNoNamespaceSpringAi() {
    assertThat(propriedades.getProperty("spring.ai.model.chat"))
        .isEqualTo("${AI_CHAT_PROVIDER:ollama}");
    // Fixo de proposito: trocar o modelo de embedding obriga a reindexar a legislacao.
    assertThat(propriedades.getProperty("spring.ai.model.embedding")).isEqualTo("ollama");
    assertThat(propriedades.getProperty("spring.ai.ollama.embedding.options.model"))
        .isEqualTo("bge-m3");
    // Nenhuma chave literal no repositorio.
    assertThat(propriedades.getProperty("spring.ai.openai.api-key")).isEqualTo("${AI_API_KEY:}");
    // Puxar modelo na subida bloquearia o backend; quem baixa e o ollama-init do compose.
    assertThat(propriedades.getProperty("spring.ai.ollama.init.pull-model-strategy"))
        .isEqualTo("${AI_OLLAMA_PULL_STRATEGY:never}");
    // Prompt e resposta carregam dados do produtor: nunca em log.
    assertThat(propriedades.getProperty("spring.ai.chat.observations.log-prompt"))
        .isEqualTo("false");
    assertThat(propriedades.getProperty("spring.ai.chat.observations.log-completion"))
        .isEqualTo("false");
    assertThat(propriedades.getProperty("spring.http.client.read-timeout")).isNotBlank();
  }

  @Test
  @DisplayName("graceful shutdown continua em server.shutdown")
  void gracefulShutdown() {
    assertThat(propriedades.getProperty("server.shutdown")).isEqualTo("graceful");
  }

  @Test
  @DisplayName("nenhum YAML de profile esconde chave de spring.* embaixo de outro bloco de topo")
  void nenhumYamlEscondeChavesDoSpring() throws IOException {
    Resource[] yamls = new PathMatchingResourcePatternResolver()
        .getResources("classpath*:application*.yml");
    // application.yml, -dev, -homolog, -prod (main) e -test (test): se o padrao nao achar os
    // arquivos, o teste passaria sem verificar nada.
    assertThat(yamls).hasSizeGreaterThanOrEqualTo(5);

    List<String> escondidas = new ArrayList<>();
    for (Resource yaml : yamls) {
      YamlPropertiesFactoryBean fabrica = new YamlPropertiesFactoryBean();
      fabrica.setResources(yaml);
      for (String chave : fabrica.getObject().stringPropertyNames()) {
        String[] segmentos = chave.split("\\.");
        // server.servlet.* legitimo (context-path, session) tambem existe; so o multipart e do
        // spring.servlet.
        boolean servletLegitimo = segmentos[0].equals("server")
            && segmentos.length > 2
            && segmentos[1].equals("servlet")
            && !segmentos[2].equals("multipart");
        if (!segmentos[0].equals("spring")
            && segmentos.length > 1
            && SUBNAMESPACES_DO_SPRING.contains(segmentos[1])
            && !servletLegitimo) {
          escondidas.add(yaml.getFilename() + ": " + chave);
        }
      }
    }
    assertThat(escondidas).isEmpty();
  }

  @Test
  @DisplayName("producao nao publica a documentacao OpenAPI (issue #93)")
  void producaoFechaOpenApi() {
    YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
    yaml.setResources(new ClassPathResource("application-prod.yml"));
    Properties prod = yaml.getObject();

    // O SecurityConfig lista /v3/api-docs e /swagger-ui como rotas publicas — desejavel em dev e
    // homolog, indesejavel em prod: publicar o contrato inteiro amplia a superficie de ataque sem
    // ganho, porque quem consome em prod e o proprio frontend.
    assertThat(prod.getProperty("springdoc.api-docs.enabled")).isEqualTo("false");
    assertThat(prod.getProperty("springdoc.swagger-ui.enabled")).isEqualTo("false");
  }

  @Test
  @DisplayName("producao nao expoe detalhes de health")
  void producaoNaoExpoeDetalhesDeHealth() {
    YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
    yaml.setResources(new ClassPathResource("application-prod.yml"));

    assertThat(yaml.getObject().getProperty("management.endpoint.health.show-details"))
        .isEqualTo("never");
  }
}
