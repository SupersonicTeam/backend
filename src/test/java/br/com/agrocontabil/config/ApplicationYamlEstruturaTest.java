package br.com.agrocontabil.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/**
 * Trava a estrutura do {@code application.yml} base (issue #125).
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
  @DisplayName("graceful shutdown continua em server.shutdown")
  void gracefulShutdown() {
    assertThat(propriedades.getProperty("server.shutdown")).isEqualTo("graceful");
  }

  @Test
  @DisplayName("nenhuma chave de spring.* escondida embaixo de server.*")
  void nadaDeSpringEmbaixoDeServer() {
    assertThat(propriedades.stringPropertyNames())
        .noneMatch(chave -> chave.startsWith("server.jpa.")
            || chave.startsWith("server.liquibase.")
            || chave.startsWith("server.servlet.multipart."));
  }
}
