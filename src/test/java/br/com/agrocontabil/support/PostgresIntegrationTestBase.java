package br.com.agrocontabil.support;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Base dos testes de integração contra PostgreSQL 16 real (issue #152): sobe o container, aplica o
 * changelog do Liquibase e roda o Hibernate em {@code validate}, como em produção. Os fluxos aqui
 * provam o que o H2 dos demais testes não prova: o schema das migrations e o SQL do PostgreSQL.
 *
 * <p>Sem Docker a classe é ignorada ({@code disabledWithoutDocker}); a CI roda com Docker.
 * Cada subclasse ganha o seu contexto e o seu container ({@code DirtiesContext}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@DirtiesContext(classMode = ClassMode.AFTER_CLASS)
public abstract class PostgresIntegrationTestBase {

  /** Mesma imagem do compose: o changelog exige a extensão {@code vector} (changeSet 0026). */
  @Container
  @SuppressWarnings("resource") // ciclo de vida gerenciado pela extensão @Testcontainers
  protected static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:0.8.6-pg16")
          .asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("agrocontabil_fluxos_test")
          .withUsername("agrocontabil")
          .withPassword("test");

  /** Tabelas do domínio que os testes esvaziam; as de referência (NCM, CFOP, alíquotas) ficam. */
  private static final List<String> TABELAS_DO_DOMINIO = List.of(
      "registro_auditoria", "nota_fiscal_validacao", "nota_fiscal_revisao", "apuracao_mensal",
      "guias_fiscais", "nota_fiscal", "carteira_vinculo", "sessao_refresh", "redefinicao_senha",
      "credencial_usuario", "contadores", "escritorio_slug_anterior", "escritorio",
      "produtores");

  @DynamicPropertySource
  static void propriedades(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    registry.add("spring.liquibase.enabled", () -> "true");
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
  }

  @Autowired protected JdbcTemplate jdbc;

  /**
   * Esvazia as tabelas do domínio, filhos antes dos pais, entre os testes da classe. Sem TRUNCATE
   * ... CASCADE de propósito: ele esvaziaria também as tabelas de referência que apontam para elas
   * (os modelos de obrigação, por exemplo).
   */
  protected void limparDominio() {
    TABELAS_DO_DOMINIO.forEach(tabela -> jdbc.update("DELETE FROM " + tabela));
  }
}
