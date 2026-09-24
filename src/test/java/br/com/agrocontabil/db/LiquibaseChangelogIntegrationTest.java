package br.com.agrocontabil.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
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

/**
 * Executa o changelog do Liquibase contra um PostgreSQL 16 real (Onda 3, issue #95).
 *
 * <p><strong>A lacuna que este teste fecha:</strong> o profile {@code test} roda com
 * {@code spring.liquibase.enabled=false} e {@code ddl-auto: create-drop}, porque o changelog usa
 * SQL específico do PostgreSQL ({@code gen_random_uuid()}, {@code jsonb}, índice parcial) que o H2
 * não aceita. Consequência: <em>nenhum</em> dos outros testes executa uma migration. Todos validam
 * o schema que o Hibernate deriva das entidades — não o schema que o Liquibase cria em produção.
 * Um changeSet com tipo incompatível, tabela errada ou default que não preenche passaria a suíte
 * inteira verde e falharia ao subir a aplicação.
 *
 * <p>São duas verificações, e a primeira é implícita e a mais forte:
 * <ol>
 *   <li>o contexto <strong>só sobe</strong> se o changelog inteiro aplicar sem erro e se o
 *       {@code ddl-auto: validate} concordar que o schema resultante corresponde às entidades JPA —
 *       ou seja, qualquer divergência entidade ↔ migration falha o teste;</li>
 *   <li>as asserções abaixo conferem, no {@code information_schema}, o contrato exato da coluna
 *       {@code version} criada pelo changeSet {@code 0022}.</li>
 * </ol>
 *
 * <p><strong>Docker:</strong> {@code disabledWithoutDocker = true} faz o teste ser <em>ignorado</em>
 * em máquina sem Docker e executado na CI ({@code ubuntu-latest} tem Docker). As dependências do
 * Testcontainers já existiam no {@code pom.xml} sem nenhum teste usá-las.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@DirtiesContext(classMode = ClassMode.AFTER_CLASS)
class LiquibaseChangelogIntegrationTest {

  @Container
  @SuppressWarnings("resource") // ciclo de vida gerenciado pela extensao @Testcontainers
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("agrocontabil_migration_test")
          .withUsername("agrocontabil")
          .withPassword("test");

  /**
   * Aponta o contexto para o container e reverte, só nesta classe, as duas escolhas do profile
   * {@code test} que impedem a migration de rodar: liga o Liquibase e troca o
   * {@code create-drop} por {@code validate}, que é o modo usado em produção.
   */
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

  @Autowired
  private DataSource dataSource;

  @Test
  @DisplayName("changeSet 0022 cria nota_fiscal.version como BIGINT NOT NULL com default 0")
  void changeSet0022CriaColunaVersionComContratoEsperado() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    Map<String, Object> coluna = jdbc.queryForMap("""
        SELECT data_type, is_nullable, column_default
          FROM information_schema.columns
         WHERE table_name = 'nota_fiscal' AND column_name = 'version'
        """);

    assertThat(coluna.get("data_type")).isEqualTo("bigint");
    // NOT NULL e o que garante que o Hibernate sempre gerencie a versao.
    assertThat(coluna.get("is_nullable")).isEqualTo("NO");
    // O default preenche as linhas ja existentes na propria adicao da coluna — e o que permite
    // NOT NULL numa tabela com dados, e o que cobre INSERT feito fora do ORM.
    assertThat(String.valueOf(coluna.get("column_default"))).contains("0");
  }

  @Test
  @DisplayName("uma nota inserida fora do ORM recebe version 0 pelo default da coluna")
  void insertForaDoOrmRecebeVersionZero() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    String produtorId = "11111111-1111-1111-1111-111111111111";
    String notaId = "22222222-2222-2222-2222-222222222222";

    jdbc.update(
        "INSERT INTO produtores (id, nome_completo) VALUES (?::uuid, ?)",
        produtorId, "Produtor Migration Test");
    // Note que o INSERT NAO menciona a coluna version: e exatamente o caso das linhas que já
    // existiam quando a migration rodou.
    jdbc.update("""
        INSERT INTO nota_fiscal (
            id, numero, serie, modelo, natureza_operacao, data_emissao, produtor_id,
            destinatario_nome, destinatario_cpf_cnpj, valor_total_produtos, valor_total_nota,
            status, tipo_documento, origem)
        VALUES (?::uuid, 1, 1, '55', 'Venda', now(), ?::uuid,
            'Comprador', '12345678901', 10.00, 10.00, 'RASCUNHO', 'NFE', 'PROPRIA')
        """, notaId, produtorId);

    Long version = jdbc.queryForObject(
        "SELECT version FROM nota_fiscal WHERE id = ?::uuid", Long.class, notaId);
    assertThat(version).isZero();

    jdbc.update("DELETE FROM nota_fiscal WHERE id = ?::uuid", notaId);
    jdbc.update("DELETE FROM produtores WHERE id = ?::uuid", produtorId);
  }
}
