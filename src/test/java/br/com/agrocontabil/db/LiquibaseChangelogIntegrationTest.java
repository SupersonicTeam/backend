package br.com.agrocontabil.db;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.config.SchedulingConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
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
import org.testcontainers.utility.DockerImageName;

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
 *       {@code version} criada pelo changeSet {@code 0022} e da tabela {@code shedlock} do
 *       changeSet {@code 0023} (issue #87) — esta última também com um ciclo de lock real, porque o
 *       {@code usingDbTime()} do ShedLock gera SQL específico de cada banco e o H2 dos demais
 *       testes não prova o dialeto do PostgreSQL.</li>
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
      // Mesma imagem do compose: a partir do changeSet 0026 o changelog exige a extensao vector
      // (issue #156), que o postgres:16-alpine nao tem.
      new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:0.8.6-pg16")
          .asCompatibleSubstituteFor("postgres"))
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

  @Autowired
  private LockProvider lockProvider;

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

  @Test
  @DisplayName("changeSet 0025 cria assistente_interacao sem coluna para pergunta ou resposta (#144)")
  void changeSet0025CriaAuditoriaSemConteudo() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    List<String> colunas = jdbc.queryForList(
        "SELECT column_name FROM information_schema.columns"
            + " WHERE table_name = 'assistente_interacao' ORDER BY column_name",
        String.class);

    assertThat(colunas).containsExactly(
        "chamadas_funcao", "criado_em", "desfecho", "funcoes", "id", "latencia_ms", "modelo",
        "produtor_id", "rota", "tamanho_pergunta", "tokens_entrada", "tokens_saida",
        "trechos_citados");
    // A auditoria registra o que o sistema fez, nunca o que o usuario disse.
    assertThat(colunas).noneMatch(c -> c.contains("resposta") || c.equals("pergunta")
        || c.equals("texto"));

    Integer fks = jdbc.queryForObject(
        "SELECT COUNT(*) FROM information_schema.table_constraints"
            + " WHERE table_name = 'assistente_interacao' AND constraint_type = 'FOREIGN KEY'"
            + " AND constraint_name = 'fk_assistente_interacao_produtor'",
        Integer.class);
    assertThat(fks).isEqualTo(1);
  }

  @Test
  @DisplayName("changeSet 0026 cria legislacao_trecho no formato do PgVectorStore (#156)")
  void changeSet0026CriaBaseDeLegislacao() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    assertThat(jdbc.queryForObject(
        "SELECT COUNT(*) FROM pg_extension WHERE extname = 'vector'", Integer.class)).isEqualTo(1);
    List<String> colunas = jdbc.queryForList(
        "SELECT column_name FROM information_schema.columns"
            + " WHERE table_name = 'legislacao_trecho' ORDER BY column_name",
        String.class);
    // conteudo_tsv (0026) foi trocada por conteudo_tsv_sem_acento na 0027 (#146).
    assertThat(colunas).containsExactly("content", "conteudo_tsv_sem_acento", "dispositivo",
        "embedding", "hash", "id", "metadata", "norma", "vigencia_fim", "vigencia_inicio");
    List<String> indices = jdbc.queryForList(
        "SELECT indexname FROM pg_indexes WHERE tablename = 'legislacao_trecho'", String.class);
    assertThat(indices).contains("uk_legislacao_trecho_hash", "idx_legislacao_trecho_embedding",
        "idx_legislacao_trecho_tsv_sem_acento", "idx_legislacao_trecho_dispositivo")
        .doesNotContain("idx_legislacao_trecho_tsv");
    // As colunas geradas leem o metadata que o PgVectorStore grava.
    jdbc.update("INSERT INTO legislacao_trecho (id, content, metadata, embedding) VALUES"
        + " ('33333333-3333-3333-3333-333333333333'::uuid, 'Ficam reduzidas as alíquotas da"
        + " redução', ?::json, array_fill(0.1::real, ARRAY[1024])::vector)",
        "{\"hash\":\"h1\",\"norma\":\"LC 214/2025\",\"dispositivo\":\"art. 1\","
            + "\"vigenciaInicio\":\"2025-01-16\",\"vigenciaFim\":\"9999-12-31\"}");
    // 0027: a busca textual ignora o acento dos dois lados ("aliquota" casa com "alíquotas").
    Map<String, Object> linha = jdbc.queryForMap("SELECT hash, norma, vigencia_fim,"
        + " conteudo_tsv_sem_acento @@ to_tsquery('portuguese', 'reduzir & aliquota & reducao')"
        + " AS achou FROM legislacao_trecho");
    assertThat(linha).containsEntry("hash", "h1").containsEntry("norma", "LC 214/2025")
        .containsEntry("vigencia_fim", "9999-12-31").containsEntry("achou", true);
    jdbc.update("DELETE FROM legislacao_trecho");
  }

  @Test
  @DisplayName("changeSet 0023 cria a tabela shedlock com o contrato do ShedLock (issue #87)")
  void changeSet0023CriaTabelaShedlock() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    List<Map<String, Object>> colunas = jdbc.queryForList("""
        SELECT column_name, data_type, is_nullable
          FROM information_schema.columns
         WHERE table_name = 'shedlock'
         ORDER BY column_name
        """);

    assertThat(colunas)
        .extracting(c ->
            c.get("column_name") + ":" + c.get("data_type") + ":" + c.get("is_nullable"))
        .containsExactly(
            "lock_until:timestamp without time zone:NO",
            "locked_at:timestamp without time zone:NO",
            "locked_by:character varying:NO",
            "name:character varying:NO");
  }

  @Test
  @DisplayName("lock com relogio do banco no PostgreSQL: exclusivo ate liberar (issue #87)")
  void lockComRelogioDoBancoEhExclusivoNoPostgres() {
    LockConfiguration config = new LockConfiguration(
        Instant.now(), "migration-test-lock", Duration.ofMinutes(5), Duration.ZERO);

    Optional<SimpleLock> primeira = lockProvider.lock(config);
    assertThat(primeira).as("lock livre deveria ser adquirido").isPresent();
    // Segunda "replica" tentando o mesmo lock enquanto a primeira o segura.
    assertThat(lockProvider.lock(config)).isEmpty();

    primeira.get().unlock();
    Optional<SimpleLock> depoisDeLiberar = lockProvider.lock(config);
    assertThat(depoisDeLiberar).isPresent();
    depoisDeLiberar.get().unlock();
  }

  @Test
  @DisplayName("keep-alive: extensao do lock com relogio do banco funciona no PostgreSQL (#87)")
  void extensaoDoLockFuncionaNoPostgres() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    LockConfiguration config = new LockConfiguration(
        Instant.now(), "migration-test-extensao", Duration.ofMinutes(1), Duration.ZERO);
    SimpleLock lock = SchedulingConfig.jdbcLockProvider(jdbc).lock(config).orElseThrow();
    java.sql.Timestamp antes = jdbc.queryForObject(
        "SELECT lock_until FROM shedlock WHERE name = 'migration-test-extensao'",
        java.sql.Timestamp.class);

    Optional<SimpleLock> estendido = lock.extend(Duration.ofMinutes(10), Duration.ZERO);

    assertThat(estendido).isPresent();
    java.sql.Timestamp depois = jdbc.queryForObject(
        "SELECT lock_until FROM shedlock WHERE name = 'migration-test-extensao'",
        java.sql.Timestamp.class);
    assertThat(depois).isAfter(antes);
    estendido.get().unlock();
  }
}
