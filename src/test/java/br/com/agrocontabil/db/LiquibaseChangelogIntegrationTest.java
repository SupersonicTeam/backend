package br.com.agrocontabil.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import br.com.agrocontabil.config.SchedulingConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.springframework.test.annotation.DirtiesContext;
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
  @DisplayName("changeSet 0037: busca_normalizada com GIN de trigrama usável no LIKE (#174)")
  void changeSet0037BuscaDasNotas() throws Exception {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    assertThat(jdbc.queryForList("SELECT extname FROM pg_extension"
        + " WHERE extname IN ('pg_trgm', 'btree_gin') ORDER BY extname", String.class))
        .containsExactly("btree_gin", "pg_trgm");
    Map<String, Object> coluna = jdbc.queryForMap("SELECT data_type, character_maximum_length,"
        + " is_nullable FROM information_schema.columns"
        + " WHERE table_name = 'nota_fiscal' AND column_name = 'busca_normalizada'");
    assertThat(coluna).containsEntry("data_type", "character varying")
        .containsEntry("character_maximum_length", 600).containsEntry("is_nullable", "YES");
    assertThat(jdbc.queryForObject("SELECT indexdef FROM pg_indexes"
        + " WHERE indexname = 'idx_nota_fiscal_busca_trgm'", String.class))
        .contains("USING gin (produtor_id, busca_normalizada gin_trgm_ops)");

    // O LIKE '%termo%' precisa casar com a classe de operador do indice (gin_trgm_ops): sem
    // varredura sequencial, so o GIN cobre busca_normalizada. Com a tabela vazia o planner
    // prefere o btree de produtor_id quando ele aparece na consulta, entao aqui vai so o LIKE; o
    // plano com produtor + LIKE sobre 200 mil notas esta medido no PR da #174.
    try (var conexao = dataSource.getConnection(); var comando = conexao.createStatement()) {
      conexao.setAutoCommit(false);
      comando.execute("SET LOCAL enable_seqscan = off");
      StringBuilder plano = new StringBuilder();
      try (var linhas = comando.executeQuery("EXPLAIN SELECT id FROM nota_fiscal"
          + " WHERE busca_normalizada LIKE '%cooperativa%'")) {
        while (linhas.next()) {
          plano.append(linhas.getString(1)).append('\n');
        }
      }
      conexao.rollback();
      assertThat(plano.toString()).contains("idx_nota_fiscal_busca_trgm");
    }
  }

  @Test
  @DisplayName("changeSet 0030: desfecho por parte do MISTO, nulo fora dele, sem texto")
  void changeSet0030PartesDoMisto() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    List<Map<String, Object>> colunas = jdbc.queryForList(
        "SELECT column_name, data_type, is_nullable FROM information_schema.columns"
            + " WHERE table_name = 'assistente_interacao' AND column_name IN"
            + " ('desfecho_dados', 'desfecho_legislacao', 'pergunta_lei_reescrita')"
            + " ORDER BY column_name");

    assertThat(colunas).extracting(c -> c.get("column_name"), c -> c.get("data_type"),
        c -> c.get("is_nullable")).containsExactly(
        tuple("desfecho_dados", "character varying", "YES"),
        tuple("desfecho_legislacao", "character varying", "YES"),
        tuple("pergunta_lei_reescrita", "boolean", "YES"));
  }

  @Test
  @DisplayName("changeSet 0029: uso semanal sem negativos e reserva concorrente no PostgreSQL (#166)")
  void changeSet0029UsoSemanalEReservaConcorrente() throws Exception {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns"
        + " WHERE table_name = 'assistente_interacao' AND column_name = 'tokens_estimados'",
        Integer.class)).isEqualTo(1);

    String titular = UUID.randomUUID().toString();
    jdbc.update("INSERT INTO assistente_uso_semanal (titular_tipo, titular_id, semana_inicio,"
        + " atualizado_em) VALUES ('PRODUTOR', ?::uuid, DATE '2026-10-12', now())", titular);
    // Estorno duplicado nunca vira credito de graca: o banco recusa contador negativo.
    assertThatThrownBy(() -> jdbc.update("UPDATE assistente_uso_semanal"
        + " SET creditos_reservados = creditos_reservados - 1 WHERE titular_id = ?::uuid", titular))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

    // A reserva do service: UPDATE condicional. 12 perguntas simultaneas, limite para 4.
    String reservar = "UPDATE assistente_uso_semanal SET creditos_reservados ="
        + " creditos_reservados + 73 WHERE titular_id = ?::uuid"
        + " AND creditos_reservados + creditos_consumidos + 73 <= 300";
    ExecutorService pool = Executors.newFixedThreadPool(12);
    CountDownLatch largada = new CountDownLatch(1);
    List<Future<Integer>> resultados = new ArrayList<>();
    try {
      for (int i = 0; i < 12; i++) {
        resultados.add(pool.submit(() -> {
          largada.await();
          return jdbc.update(reservar, titular);
        }));
      }
      largada.countDown();
      int aceitas = 0;
      for (Future<Integer> r : resultados) {
        aceitas += r.get(30, TimeUnit.SECONDS);
      }
      assertThat(aceitas).isEqualTo(4);
      assertThat(jdbc.queryForObject("SELECT creditos_reservados FROM assistente_uso_semanal"
          + " WHERE titular_id = ?::uuid", Long.class, titular)).isEqualTo(292L);
    } finally {
      pool.shutdownNow();
      jdbc.update("DELETE FROM assistente_uso_semanal WHERE titular_id = ?::uuid", titular);
    }
  }

  @Test
  @DisplayName("changeSet 0031 cria credencial_usuario com CHECK de identidade, unicidades e CASCADE")
  void changeSet0031CriaCredencialUsuario() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns"
        + " WHERE table_name = 'credencial_usuario' ORDER BY column_name", String.class))
        .containsExactly("ativo", "bloqueada_ate", "contador_id", "cpf_cnpj", "created_at", "email",
            "falhas_login", "id", "papel", "produtor_id", "senha_hash", "updated_at",
            "versao_sessao");

    String produtorId = UUID.randomUUID().toString();
    String contadorId = UUID.randomUUID().toString();
    jdbc.update("INSERT INTO produtores (id, nome_completo) VALUES (?::uuid, ?)",
        produtorId, "Produtor Credencial");
    jdbc.update("INSERT INTO contadores (id, nome_completo) VALUES (?::uuid, ?)",
        contadorId, "Contador Credencial");
    String insert = "INSERT INTO credencial_usuario (id, email, senha_hash, papel, cpf_cnpj,"
        + " produtor_id, contador_id) VALUES (?::uuid, ?, '{bcrypt}x', ?,"
        + " ?, ?::uuid, ?::uuid)";

    jdbc.update(insert, UUID.randomUUID().toString(), "p@x.com", "PRODUTOR", "52998224725",
        produtorId, null);
    // ativo nasce verdadeiro e versao_sessao em zero, pelos defaults do banco.
    assertThat(jdbc.queryForObject("SELECT ativo FROM credencial_usuario WHERE email = 'p@x.com'",
        Boolean.class)).isTrue();
    assertThat(jdbc.queryForObject(
        "SELECT versao_sessao FROM credencial_usuario WHERE email = 'p@x.com'",
        Integer.class)).isZero();

    // CHECK: papel e referencia precisam bater, e so uma referencia por linha.
    assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID().toString(), "c@x.com",
        "CONTADOR", "11222333000181", produtorId, null))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID().toString(), "c@x.com",
        "CONTADOR", "11222333000181", null, null))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    // E-mail unico; (papel, documento) unico.
    assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID().toString(), "p@x.com",
        "CONTADOR", "11222333000181", null, contadorId))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    jdbc.update(insert, UUID.randomUUID().toString(), "c@x.com", "CONTADOR", "52998224725",
        null, contadorId);

    // A credencial nao sobrevive a identidade.
    jdbc.update("DELETE FROM produtores WHERE id = ?::uuid", produtorId);
    jdbc.update("DELETE FROM contadores WHERE id = ?::uuid", contadorId);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM credencial_usuario"
        + " WHERE email IN ('p@x.com', 'c@x.com')", Integer.class)).isZero();
  }

  @Test
  @DisplayName("changeSet 0032 cria sessao_refresh com hash unico, indices e CASCADE da credencial")
  void changeSet0032CriaSessaoRefresh() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns"
        + " WHERE table_name = 'sessao_refresh' ORDER BY column_name", String.class))
        .containsExactly("credencial_id", "criado_em", "expira_em", "familia_id",
            "familia_iniciada_em", "id", "revogado_em", "token_hash", "usado_em", "versao_sessao");
    assertThat(jdbc.queryForList("SELECT indexname FROM pg_indexes"
        + " WHERE tablename = 'sessao_refresh'", String.class))
        .contains("idx_sessao_refresh_familia", "idx_sessao_refresh_credencial",
            "idx_sessao_refresh_expira_em", "uk_sessao_refresh_token_hash");

    String produtorId = UUID.randomUUID().toString();
    String credencialId = UUID.randomUUID().toString();
    jdbc.update("INSERT INTO produtores (id, nome_completo) VALUES (?::uuid, ?)",
        produtorId, "Produtor Sessao");
    jdbc.update("INSERT INTO credencial_usuario (id, email, senha_hash, papel, cpf_cnpj,"
        + " produtor_id) VALUES (?::uuid, 's@x.com', '{bcrypt}x', 'PRODUTOR', '11144477735',"
        + " ?::uuid)", credencialId, produtorId);
    String insert = "INSERT INTO sessao_refresh (id, credencial_id, familia_id, token_hash,"
        + " versao_sessao, familia_iniciada_em, criado_em, expira_em) VALUES (?::uuid, ?::uuid,"
        + " gen_random_uuid(), ?, 0, now(), now(), now() + interval '7 days')";
    jdbc.update(insert, UUID.randomUUID().toString(), credencialId, "a".repeat(64));
    // O mesmo hash duas vezes nunca: a busca pelo token precisa achar uma linha so.
    assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID().toString(), credencialId,
        "a".repeat(64)))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

    // Apagar a identidade leva a credencial e, com ela, as sessoes.
    jdbc.update("DELETE FROM produtores WHERE id = ?::uuid", produtorId);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sessao_refresh"
        + " WHERE credencial_id = ?::uuid", Integer.class, credencialId)).isZero();
  }

  @Test
  @DisplayName("changeSets 0038/0039: carteira do escritorio, um vinculo aberto por par (#177, #228)")
  void changeSet0038CarteiraDoEscritorio() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables"
        + " WHERE table_name = 'contador_produtor'", Integer.class)).isZero();
    assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns"
        + " WHERE table_name = 'carteira_vinculo' ORDER BY column_name", String.class))
        .containsExactly("created_at", "escritorio_id", "fim", "id", "inicio", "observacoes",
            "produtor_id", "responsavel_id", "situacao", "updated_at");
    assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns"
        + " WHERE table_name = 'convite_carteira' ORDER BY column_name", String.class))
        .containsExactly("convidado_por", "criado_em", "email", "escritorio_id", "expira_em",
            "id", "produtor_id", "respondido_em", "situacao");
    assertThat(jdbc.queryForObject("SELECT col_description('convite_carteira'::regclass,"
        + " (SELECT ordinal_position FROM information_schema.columns"
        + " WHERE table_name = 'convite_carteira' AND column_name = 'email')::int)",
        String.class)).contains("cifrado");

    String escritorioId = UUID.randomUUID().toString();
    String produtorId = UUID.randomUUID().toString();
    jdbc.update("INSERT INTO escritorio (id, nome) VALUES (?::uuid, 'Escritorio')", escritorioId);
    jdbc.update("INSERT INTO produtores (id, nome_completo) VALUES (?::uuid, 'Produtor')",
        produtorId);
    String vinculo = "INSERT INTO carteira_vinculo (id, escritorio_id, produtor_id, situacao,"
        + " inicio) VALUES (gen_random_uuid(), ?::uuid, ?::uuid, ?, now())";
    jdbc.update(vinculo, escritorioId, produtorId, "ENCERRADO");
    jdbc.update(vinculo, escritorioId, produtorId, "ENCERRADO");
    jdbc.update(vinculo, escritorioId, produtorId, "ATIVO");
    // Indice unico parcial: so um vinculo nao encerrado por par.
    assertThatThrownBy(() -> jdbc.update(vinculo, escritorioId, produtorId, "SUSPENSO"))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

    String convite = "INSERT INTO convite_carteira (id, escritorio_id, email, situacao,"
        + " criado_em, expira_em) VALUES (gen_random_uuid(), ?::uuid, 'v1:abc', ?, now(), now())";
    jdbc.update(convite, escritorioId, "RECUSADO");
    jdbc.update(convite, escritorioId, "PENDENTE");
    assertThatThrownBy(() -> jdbc.update(convite, escritorioId, "PENDENTE"))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

    String etiquetaId = UUID.randomUUID().toString();
    jdbc.update("INSERT INTO etiqueta_carteira (id, escritorio_id, nome, nome_normalizado)"
        + " VALUES (?::uuid, ?::uuid, 'Soja', 'soja')", etiquetaId, escritorioId);
    jdbc.update("INSERT INTO carteira_vinculo_etiqueta (vinculo_id, etiqueta_id)"
        + " SELECT id, ?::uuid FROM carteira_vinculo WHERE situacao = 'ATIVO'"
        + " AND escritorio_id = ?::uuid", etiquetaId, escritorioId);

    // CASCADE: o produtor apagado leva o vinculo e a etiqueta aplicada; o escritorio, o resto.
    jdbc.update("DELETE FROM produtores WHERE id = ?::uuid", produtorId);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM carteira_vinculo"
        + " WHERE escritorio_id = ?::uuid", Integer.class, escritorioId)).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM carteira_vinculo_etiqueta"
        + " WHERE etiqueta_id = ?::uuid", Integer.class, etiquetaId)).isZero();
    jdbc.update("DELETE FROM escritorio WHERE id = ?::uuid", escritorioId);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM convite_carteira"
        + " WHERE escritorio_id = ?::uuid", Integer.class, escritorioId)).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM etiqueta_carteira"
        + " WHERE escritorio_id = ?::uuid", Integer.class, escritorioId)).isZero();
  }

  @Test
  @DisplayName("changeSet 0039: contador e vinculos antigos viram escritorio unipessoal (#228)")
  void changeSet0039MigraContadorParaEscritorio() throws Exception {
    JdbcTemplate admin = new JdbcTemplate(dataSource);
    admin.execute("DROP DATABASE IF EXISTS migra_0039");
    admin.execute("CREATE DATABASE migra_0039");
    String url = POSTGRES.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/migra_0039$1");
    try (java.sql.Connection conexao = java.sql.DriverManager.getConnection(
        url, POSTGRES.getUsername(), POSTGRES.getPassword())) {
      liquibase.database.Database banco = liquibase.database.DatabaseFactory.getInstance()
          .findCorrectDatabaseImplementation(new liquibase.database.jvm.JdbcConnection(conexao));
      liquibase.Liquibase liquibase = new liquibase.Liquibase(
          "db/changelog/db.changelog-master.yaml",
          new liquibase.resource.ClassLoaderResourceAccessor(), banco);
      List<liquibase.changelog.ChangeSet> pendentes = liquibase.listUnrunChangeSets(
          new liquibase.Contexts(), new liquibase.LabelExpression());
      int ate0038 = 0;
      while (!pendentes.get(ate0038).getId().startsWith("0039-")) {
        ate0038++;
      }
      liquibase.update(ate0038, "");

      // Dados como eram antes da 0039: carteira, etiqueta e convite do contador.
      String contadorId = UUID.randomUUID().toString();
      String produtorId = UUID.randomUUID().toString();
      JdbcTemplate jdbc = new JdbcTemplate(
          new org.springframework.jdbc.datasource.SingleConnectionDataSource(conexao, true));
      jdbc.update("INSERT INTO contadores (id, nome_completo) VALUES (?::uuid, 'Campo Certo')",
          contadorId);
      jdbc.update("INSERT INTO produtores (id, nome_completo) VALUES (?::uuid, 'Fazenda')",
          produtorId);
      jdbc.update("INSERT INTO carteira_vinculo (id, contador_id, produtor_id, situacao, inicio)"
          + " VALUES (gen_random_uuid(), ?::uuid, ?::uuid, 'ATIVO', now())", contadorId,
          produtorId);
      jdbc.update("INSERT INTO etiqueta_carteira (id, contador_id, nome, nome_normalizado)"
          + " VALUES (gen_random_uuid(), ?::uuid, 'Soja', 'soja')", contadorId);
      jdbc.update("INSERT INTO convite_carteira (id, contador_id, email, situacao, criado_em,"
          + " expira_em) VALUES (gen_random_uuid(), ?::uuid, 'v1:x', 'PENDENTE', now(), now())",
          contadorId);

      liquibase.update("");

      assertThat(jdbc.queryForMap("SELECT escritorio_id::text AS escritorio,"
          + " funcao_escritorio AS funcao FROM contadores WHERE id = ?::uuid", contadorId))
          .containsEntry("escritorio", contadorId).containsEntry("funcao", "ADMINISTRADOR");
      assertThat(jdbc.queryForObject("SELECT nome FROM escritorio WHERE id = ?::uuid",
          String.class, contadorId)).isEqualTo("Campo Certo");
      assertThat(jdbc.queryForMap("SELECT escritorio_id::text AS escritorio,"
          + " responsavel_id::text AS responsavel FROM carteira_vinculo"
          + " WHERE produtor_id = ?::uuid", produtorId))
          .containsEntry("escritorio", contadorId).containsEntry("responsavel", contadorId);
      assertThat(jdbc.queryForObject("SELECT escritorio_id::text FROM etiqueta_carteira",
          String.class)).isEqualTo(contadorId);
      assertThat(jdbc.queryForMap("SELECT escritorio_id::text AS escritorio,"
          + " convidado_por::text AS por FROM convite_carteira"))
          .containsEntry("escritorio", contadorId).containsEntry("por", contadorId);
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pg_indexes"
          + " WHERE indexname IN ('uk_carteira_vinculo_aberto_escritorio',"
          + " 'uk_convite_carteira_pendente_escritorio')", Integer.class)).isEqualTo(2);
      // O registro de auditoria guarda o escritorio de quem agiu.
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns"
          + " WHERE table_name = 'registro_auditoria' AND column_name = 'escritorio_id'",
          Integer.class)).isEqualTo(1);
    } finally {
      admin.execute("DROP DATABASE IF EXISTS migra_0039 WITH (FORCE)");
    }
  }

  @Test
  @DisplayName("changeSet 0040: obrigacoes com 11 modelos, CHECK de prazo e uma versao por modelo")
  void changeSet0040ObrigacoesFiscais() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM obrigacao_fiscal WHERE modelo"
        + " AND escritorio_id IS NULL AND ativa", Integer.class)).isEqualTo(11);
    assertThat(jdbc.queryForObject("SELECT regimes_tributarios FROM obrigacao_fiscal"
        + " WHERE titulo = 'DCTFWeb'", String.class)).isEqualTo("PRESUMIDO,REAL");
    assertThat(jdbc.queryForMap("SELECT dia, mes, ajuste_fim_de_semana FROM obrigacao_fiscal"
        + " WHERE titulo = 'ECD'"))
        .containsEntry("dia", 31).containsEntry("mes", 6)
        .containsEntry("ajuste_fim_de_semana", "ANTECIPA");

    String escritorioId = UUID.randomUUID().toString();
    jdbc.update("INSERT INTO escritorio (id, nome) VALUES (?::uuid, 'Escritorio')", escritorioId);
    String versao = "INSERT INTO obrigacao_fiscal (id, escritorio_id, modelo, modelo_id, titulo,"
        + " periodicidade, dia, ajuste_fim_de_semana, ativa) VALUES (gen_random_uuid(), ?::uuid,"
        + " false, '00000000-0000-4000-8181-000000000001', 'DAS', 'MENSAL', 18, 'NENHUM', true)";
    jdbc.update(versao, escritorioId);
    assertThatThrownBy(() -> jdbc.update(versao, escritorioId))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    // Mensal com mes, e linha de escritorio marcada como modelo: barradas pelo CHECK.
    assertThatThrownBy(() -> jdbc.update("INSERT INTO obrigacao_fiscal (id, escritorio_id,"
        + " modelo, titulo, periodicidade, dia, mes, ajuste_fim_de_semana, ativa) VALUES"
        + " (gen_random_uuid(), ?::uuid, false, 'X', 'MENSAL', 10, 3, 'NENHUM', true)",
        escritorioId))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThatThrownBy(() -> jdbc.update("INSERT INTO obrigacao_fiscal (id, escritorio_id,"
        + " modelo, titulo, periodicidade, dia, ajuste_fim_de_semana, ativa) VALUES"
        + " (gen_random_uuid(), ?::uuid, true, 'X', 'MENSAL', 10, 'NENHUM', true)",
        escritorioId))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

    // CASCADE do escritorio; o modelo fica.
    jdbc.update("DELETE FROM escritorio WHERE id = ?::uuid", escritorioId);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM obrigacao_fiscal"
        + " WHERE escritorio_id = ?::uuid", Integer.class, escritorioId)).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM obrigacao_fiscal WHERE modelo",
        Integer.class)).isEqualTo(11);
  }

  @Test
  @DisplayName("changeSet 0041: historico de revisao da nota, CASCADE da nota (#178)")
  void changeSet0041RevisaoDaNota() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns"
        + " WHERE table_name = 'nota_fiscal_revisao' ORDER BY column_name", String.class))
        .containsExactly("acao", "id", "motivo", "nota_fiscal_id", "ocorrido_em", "papel",
            "usuario_id");
    assertThat(jdbc.queryForObject("SELECT character_maximum_length FROM information_schema.columns"
        + " WHERE table_name = 'nota_fiscal' AND column_name = 'status'", Integer.class))
        .as("AGUARDANDO_APROVACAO tem 20 caracteres").isGreaterThanOrEqualTo(20);

    String produtorId = UUID.randomUUID().toString();
    String notaId = UUID.randomUUID().toString();
    jdbc.update("INSERT INTO produtores (id, nome_completo) VALUES (?::uuid, 'Produtor')",
        produtorId);
    jdbc.update("INSERT INTO nota_fiscal (id, produtor_id, numero, serie, modelo,"
        + " natureza_operacao, data_emissao, destinatario_nome, destinatario_cpf_cnpj,"
        + " valor_total_produtos, valor_total_nota, status) VALUES (?::uuid, ?::uuid, 1, 1, '55',"
        + " 'Venda', now(), 'Comprador', '12345678901', 1, 1, 'AGUARDANDO_APROVACAO')",
        notaId, produtorId);
    jdbc.update("INSERT INTO nota_fiscal_revisao (id, nota_fiscal_id, acao, ocorrido_em)"
        + " VALUES (gen_random_uuid(), ?::uuid, 'APROVACAO_CADUCADA', now())", notaId);

    jdbc.update("DELETE FROM nota_fiscal WHERE id = ?::uuid", notaId);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nota_fiscal_revisao"
        + " WHERE nota_fiscal_id = ?::uuid", Integer.class, notaId)).isZero();
    jdbc.update("DELETE FROM produtores WHERE id = ?::uuid", produtorId);
  }

  @Test
  @DisplayName("changeSet 0042: pendencia e anexo em bytea, CASCADE do produtor e do escritorio")
  void changeSet0042Pendencia() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns"
        + " WHERE table_name = 'pendencia' ORDER BY column_name", String.class))
        .containsExactly("created_at", "criado_por", "data_limite", "descricao", "escritorio_id",
            "id", "motivo", "observacoes", "produtor_id", "status", "tipos_documento", "titulo",
            "updated_at");
    assertThat(jdbc.queryForObject("SELECT data_type FROM information_schema.columns"
        + " WHERE table_name = 'pendencia_anexo' AND column_name = 'conteudo'", String.class))
        .isEqualTo("bytea");

    String escritorioId = UUID.randomUUID().toString();
    String produtorId = UUID.randomUUID().toString();
    jdbc.update("INSERT INTO escritorio (id, nome) VALUES (?::uuid, 'Escritorio')", escritorioId);
    jdbc.update("INSERT INTO produtores (id, nome_completo) VALUES (?::uuid, 'Produtor')",
        produtorId);
    String pendenciaId = UUID.randomUUID().toString();
    String insertPendencia = "INSERT INTO pendencia (id, escritorio_id, produtor_id, titulo,"
        + " status) VALUES (?::uuid, ?::uuid, ?::uuid, 'Extrato', 'PENDENTE')";
    String insertAnexo = "INSERT INTO pendencia_anexo (id, pendencia_id, nome_arquivo, mime,"
        + " tamanho, sha256, conteudo, enviado_em) VALUES (gen_random_uuid(), ?::uuid, 'a.pdf',"
        + " 'application/pdf', 4, ?, ?, now())";
    jdbc.update(insertPendencia, pendenciaId, escritorioId, produtorId);
    jdbc.update(insertAnexo, pendenciaId, "c".repeat(64), new byte[] {'%', 'P', 'D', 'F'});

    jdbc.update("DELETE FROM produtores WHERE id = ?::uuid", produtorId);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pendencia_anexo"
        + " WHERE pendencia_id = ?::uuid", Integer.class, pendenciaId)).isZero();

    jdbc.update("INSERT INTO produtores (id, nome_completo) VALUES (?::uuid, 'Produtor')",
        produtorId);
    jdbc.update(insertPendencia, pendenciaId, escritorioId, produtorId);
    jdbc.update("DELETE FROM escritorio WHERE id = ?::uuid", escritorioId);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pendencia WHERE id = ?::uuid",
        Integer.class, pendenciaId)).isZero();
    jdbc.update("DELETE FROM produtores WHERE id = ?::uuid", produtorId);
  }

  @Test
  @DisplayName("changeSet 0047: IBS de 2026 todo estadual; 0,05% + 0,05% em 2027-2028 (#277)")
  void changeSet0047IbsPorEsfera() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    String vigencias = "SELECT COUNT(*) FROM aliquota_ibs_uf WHERE data_inicio = ?::date"
        + " AND data_fim = ?::date AND aliquota_estadual = ? AND aliquota_municipal = ?";

    assertThat(jdbc.queryForObject(vigencias, Integer.class, "2026-01-01", "2026-12-31",
        new java.math.BigDecimal("0.0010"), java.math.BigDecimal.ZERO)).isEqualTo(27);
    assertThat(jdbc.queryForObject(vigencias, Integer.class, "2027-01-01", "2028-12-31",
        new java.math.BigDecimal("0.0005"), new java.math.BigDecimal("0.0005"))).isEqualTo(27);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM aliquota_ibs_uf", Integer.class))
        .isEqualTo(54);
  }

  @Test
  @DisplayName("changeSet 0046: NCMs inexistentes trocados pelos codigos oficiais (#282)")
  void changeSet0046NcmCodigosOficiais() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    assertThat(jdbc.queryForList("SELECT codigo FROM ncm_agropecuario WHERE codigo IN"
        + " ('12019000', '12011000', '04072100', '04072900', '01039100', '01039200',"
        + " '12010090', '12010010', '04070090', '01039000') ORDER BY codigo", String.class))
        .containsExactly("01039100", "01039200", "04072100", "04072900", "12011000", "12019000");
    // A reducao padrao (ano-teste) que o 0046 deu ao ovo saiu com a coluna no 0048 (#272).
  }

  @Test
  @DisplayName("changeSet 0049: base cheia so nas notas nao emitidas (#278)")
  void changeSet0049BaseCheia() throws Exception {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    // O changeSet ja rodou (banco vazio); aqui, a mesma regra sobre linhas reais nao muda a
    // nota emitida e alinha a nao emitida.
    String produtorId = UUID.randomUUID().toString();
    jdbc.update("INSERT INTO produtores (id, nome_completo) VALUES (?::uuid, 'Produtor')",
        produtorId);
    String nota = "INSERT INTO nota_fiscal (id, produtor_id, numero, serie, modelo,"
        + " natureza_operacao, data_emissao, destinatario_nome, destinatario_cpf_cnpj,"
        + " valor_total_produtos, valor_total_nota, status, origem, tipo_documento)"
        + " VALUES (?::uuid, ?::uuid, ?, 1, '55', 'Venda', now(), 'C', '52998224725', 1000,"
        + " 1000, ?, 'PROPRIA', 'NFE')";
    String item = "INSERT INTO nota_fiscal_item (id, nota_fiscal_id, numero_item, descricao,"
        + " ncm, cfop, unidade, quantidade, valor_unitario, valor_total, base_calculo_ibs_cbs)"
        + " VALUES (gen_random_uuid(), ?::uuid, 1, 'Soja', '12019000', '5101', 'KG', 1, 1000,"
        + " 1000, 400)";
    String rascunho = UUID.randomUUID().toString();
    String emitida = UUID.randomUUID().toString();
    jdbc.update(nota, rascunho, produtorId, 1, "RASCUNHO");
    jdbc.update(nota, emitida, produtorId, 2, "AUTORIZADA");
    jdbc.update(item, rascunho);
    jdbc.update(item, emitida);

    String sql = new String(java.util.Objects.requireNonNull(getClass().getClassLoader()
        .getResourceAsStream("db/changelog/changes/0049-base-ibs-cbs-cheia.xml"))
        .readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    String update = sql.substring(sql.indexOf("UPDATE"), sql.indexOf("</sql>"))
        .replace("&lt;&gt;", "<>");
    jdbc.update(update);

    String base = "SELECT base_calculo_ibs_cbs FROM nota_fiscal_item WHERE nota_fiscal_id ="
        + " ?::uuid";
    assertThat(jdbc.queryForObject(base, java.math.BigDecimal.class, rascunho))
        .isEqualByComparingTo("1000");
    assertThat(jdbc.queryForObject(base, java.math.BigDecimal.class, emitida))
        .isEqualByComparingTo("400");
    jdbc.update("DELETE FROM nota_fiscal WHERE produtor_id = ?::uuid", produtorId);
    jdbc.update("DELETE FROM produtores WHERE id = ?::uuid", produtorId);
  }

  @Test
  @DisplayName("changeSet 0048: tabela oficial de cClassTrib e anexos carregada dos CSVs (#272)")
  void changeSet0048ClassificacaoTributaria() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM classificacao_tributaria",
        Integer.class)).isEqualTo(104);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM classificacao_tributaria_anexo_item",
        Integer.class)).isEqualTo(79);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM classificacao_tributaria_anexo_ncm",
        Integer.class)).isEqualTo(2128);
    // Alimentos com caractere fora do ASCII: o loadData leu o CSV em UTF-8.
    assertThat(jdbc.queryForObject("SELECT condicao FROM classificacao_tributaria_anexo_item"
        + " WHERE id = 1", String.class)).isEqualTo("DESTINADOS À ALIMENTAÇÃO HUMANA");
    assertThat(jdbc.queryForObject("SELECT cst || ':' || percentual_reducao_ibs FROM"
        + " classificacao_tributaria WHERE c_class_trib = '200014'", String.class))
        .isEqualTo("200:100.00");
    assertThat(jdbc.queryForList("SELECT codigo FROM ncm_agropecuario WHERE in_natura"
        + " ORDER BY codigo", String.class))
        .containsExactly("10011900", "10059010", "12019000", "52010020");
    assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns"
        + " WHERE table_name = 'ncm_agropecuario'", String.class))
        .doesNotContain("percentual_reducao_padrao");
    assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns"
        + " WHERE table_name = 'nota_fiscal_item' AND column_name LIKE '%aplicado'"
        + " ORDER BY column_name", String.class))
        .containsExactly("c_class_trib_aplicado", "percentual_reducao_aplicado");
  }

  @Test
  @DisplayName("changeSet 0045: chave de acesso unica por produtor e origem, nao global (#223)")
  void changeSet0045ChavePorProdutor() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    assertThat(jdbc.queryForList("SELECT constraint_name FROM information_schema.table_constraints"
        + " WHERE table_name = 'nota_fiscal' AND constraint_type = 'UNIQUE'", String.class))
        .contains("uk_nota_fiscal_produtor_chave_origem")
        .doesNotContain("uk_nota_fiscal_chave_acesso");

    String emitente = UUID.randomUUID().toString();
    String destinatario = UUID.randomUUID().toString();
    for (String id : new String[] {emitente, destinatario}) {
      jdbc.update("INSERT INTO produtores (id, nome_completo) VALUES (?::uuid, 'Produtor')", id);
    }
    String chave = "4".repeat(44);
    String insert = "INSERT INTO nota_fiscal (id, produtor_id, chave_acesso, numero, serie,"
        + " modelo, natureza_operacao, data_emissao, destinatario_nome, destinatario_cpf_cnpj,"
        + " valor_total_produtos, valor_total_nota, status, origem, tipo_documento)"
        + " VALUES (gen_random_uuid(), ?::uuid, ?, ?, 1, '55', 'Venda', now(), 'Comprador',"
        + " '52998224725', 100, 100, 'AUTORIZADA', ?, 'NFE')";
    // O emitente tem a PROPRIA; o destinatario importa a mesma chave.
    jdbc.update(insert, emitente, chave, 1, "PROPRIA");
    jdbc.update(insert, destinatario, chave, 1, "IMPORTADA");
    // O mesmo produtor nao importa a mesma chave duas vezes.
    assertThatThrownBy(() -> jdbc.update(insert, destinatario, chave, 2, "IMPORTADA"))
        .isInstanceOf(DataIntegrityViolationException.class);

    jdbc.update("DELETE FROM nota_fiscal WHERE chave_acesso = ?", chave);
    jdbc.update("DELETE FROM produtores WHERE id IN (?::uuid, ?::uuid)", emitente, destinatario);
  }

  @Test
  @DisplayName("changeSet 0044: notificacao com aviso unico por item e CASCADE do produtor")
  void changeSet0044Notificacao() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns"
        + " WHERE table_name = 'notificacao' ORDER BY column_name", String.class))
        .containsExactly("canal", "chave_item", "criado_em", "disparado_por", "escritorio_id",
            "id", "papel", "produtor_id", "quantidade_itens", "status", "texto", "tipo",
            "urgentes");

    String produtorId = UUID.randomUUID().toString();
    jdbc.update("INSERT INTO produtores (id, nome_completo) VALUES (?::uuid, 'Produtor')",
        produtorId);
    String insert = "INSERT INTO notificacao (id, produtor_id, tipo, canal, status, texto,"
        + " quantidade_itens, urgentes, chave_item, criado_em) VALUES (gen_random_uuid(),"
        + " ?::uuid, ?, 'WHATSAPP', 'REGISTRADA', 'texto', 1, 0, ?, now())";
    // Manuais (sem chave) nao conflitam entre si; o aviso do mesmo item e data, sim.
    jdbc.update(insert, produtorId, "MENSAGEM", null);
    jdbc.update(insert, produtorId, "MENSAGEM", null);
    jdbc.update(insert, produtorId, "AVISO_URGENTE", "imposto:x:2026-03-04");
    assertThatThrownBy(() -> jdbc.update(insert, produtorId, "AVISO_URGENTE",
        "imposto:x:2026-03-04")).isInstanceOf(DataIntegrityViolationException.class);

    jdbc.update("DELETE FROM produtores WHERE id = ?::uuid", produtorId);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notificacao WHERE produtor_id ="
        + " ?::uuid", Integer.class, produtorId)).isZero();
  }

  @Test
  @DisplayName("changeSet 0043: situacao do antivirus, anexo antigo em verificacao e so INFECTADO"
      + " sem arquivo")
  void changeSet0043AntivirusDoAnexo() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns"
        + " WHERE table_name = 'pendencia_anexo' AND column_name IN"
        + " ('situacao', 'assinatura_virus', 'verificado_em', 'libera_pendencia')"
        + " ORDER BY column_name", String.class))
        .containsExactly("assinatura_virus", "libera_pendencia", "situacao", "verificado_em");
    assertThat(jdbc.queryForObject("SELECT is_nullable FROM information_schema.columns"
        + " WHERE table_name = 'pendencia_anexo' AND column_name = 'conteudo'", String.class))
        .isEqualTo("YES");

    String escritorioId = UUID.randomUUID().toString();
    String produtorId = UUID.randomUUID().toString();
    String pendenciaId = UUID.randomUUID().toString();
    jdbc.update("INSERT INTO escritorio (id, nome) VALUES (?::uuid, 'Escritorio')", escritorioId);
    jdbc.update("INSERT INTO produtores (id, nome_completo) VALUES (?::uuid, 'Produtor')",
        produtorId);
    jdbc.update("INSERT INTO pendencia (id, escritorio_id, produtor_id, titulo, status)"
        + " VALUES (?::uuid, ?::uuid, ?::uuid, 'Extrato', 'PENDENTE')", pendenciaId,
        escritorioId, produtorId);
    String base = "INSERT INTO pendencia_anexo (id, pendencia_id, nome_arquivo, mime, tamanho,"
        + " sha256, conteudo, enviado_em%s) VALUES (gen_random_uuid(), ?::uuid, 'a.pdf',"
        + " 'application/pdf', 4, ?, ?, now()%s)";

    // Sem a coluna (como os anexos de antes da 0043): nasce em verificacao — falha fechada.
    jdbc.update(base.formatted("", ""), pendenciaId, "c".repeat(64),
        new byte[] {'%', 'P', 'D', 'F'});
    // E nao move a pendencia quando o job o liberar: o envio antigo ja a moveu.
    assertThat(jdbc.queryForObject("SELECT libera_pendencia FROM pendencia_anexo"
        + " WHERE pendencia_id = ?::uuid", Boolean.class, pendenciaId)).isFalse();
    assertThat(jdbc.queryForObject("SELECT situacao FROM pendencia_anexo WHERE pendencia_id ="
        + " ?::uuid", String.class, pendenciaId)).isEqualTo("EM_VERIFICACAO");

    String comSituacao = base.formatted(", situacao", ", ?");
    jdbc.update(comSituacao, pendenciaId, "d".repeat(64), null, "INFECTADO");
    assertThatThrownBy(() -> jdbc.update(comSituacao, pendenciaId, "e".repeat(64),
        new byte[] {1}, "INFECTADO")).isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> jdbc.update(comSituacao, pendenciaId, "f".repeat(64), null,
        "LIMPO")).isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> jdbc.update(comSituacao, pendenciaId, "g".repeat(64),
        new byte[] {1}, "SUSPEITO")).isInstanceOf(DataIntegrityViolationException.class);

    jdbc.update("DELETE FROM produtores WHERE id = ?::uuid", produtorId);
    jdbc.update("DELETE FROM escritorio WHERE id = ?::uuid", escritorioId);
  }

  @Test
  @DisplayName("changeSet 0034 cria redefinicao_senha com hash unico e CASCADE da credencial")
  void changeSet0034CriaRedefinicaoSenha() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns"
        + " WHERE table_name = 'redefinicao_senha' ORDER BY column_name", String.class))
        .containsExactly("credencial_id", "criado_em", "expira_em", "id", "token_hash", "usado_em");

    String produtorId = UUID.randomUUID().toString();
    String credencialId = UUID.randomUUID().toString();
    jdbc.update("INSERT INTO produtores (id, nome_completo) VALUES (?::uuid, ?)",
        produtorId, "Produtor Redefinicao");
    jdbc.update("INSERT INTO credencial_usuario (id, email, senha_hash, papel, cpf_cnpj,"
        + " produtor_id) VALUES (?::uuid, 'r@x.com', '{bcrypt}x', 'PRODUTOR', '52998224725',"
        + " ?::uuid)", credencialId, produtorId);
    String insert = "INSERT INTO redefinicao_senha (id, credencial_id, token_hash, criado_em,"
        + " expira_em) VALUES (gen_random_uuid(), ?::uuid, ?, now(), now() + interval '30 minutes')";
    jdbc.update(insert, credencialId, "b".repeat(64));
    assertThatThrownBy(() -> jdbc.update(insert, credencialId, "b".repeat(64)))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

    jdbc.update("DELETE FROM produtores WHERE id = ?::uuid", produtorId);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM redefinicao_senha"
        + " WHERE credencial_id = ?::uuid", Integer.class, credencialId)).isZero();
  }

  @Test
  @DisplayName("changeSet 0036 alarga as colunas de dado pessoal e as marca como cifradas (#147)")
  void changeSet0036AlargaColunasCifradas() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    String largura = "SELECT character_maximum_length FROM information_schema.columns"
        + " WHERE table_name = ? AND column_name = ?";
    String comentario = "SELECT col_description(?::regclass, ordinal_position::int)"
        + " FROM information_schema.columns WHERE table_name = ? AND column_name = ?";

    for (String tabela : List.of("produtores", "contadores", "credencial_usuario")) {
      assertThat(jdbc.queryForObject(largura, Integer.class, tabela, "cpf_cnpj")).isEqualTo(100);
      assertThat(jdbc.queryForObject(largura, Integer.class, tabela, "email")).isEqualTo(512);
      assertThat(jdbc.queryForObject(comentario, String.class, tabela, tabela, "email"))
          .startsWith("CIFRADO");
    }
    assertThat(jdbc.queryForObject(largura, Integer.class, "produtores", "telefone"))
        .isEqualTo(100);
    // credencial_usuario continua exigindo documento e e-mail.
    assertThat(jdbc.queryForObject("SELECT is_nullable FROM information_schema.columns"
        + " WHERE table_name = 'credencial_usuario' AND column_name = 'email'", String.class))
        .isEqualTo("NO");
  }

  @Test
  @DisplayName("changeSet 0035 cria registro_auditoria sem FK, que sobrevive ao produtor (#149)")
  void changeSet0035CriaRegistroAuditoria() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns"
        + " WHERE table_name = 'registro_auditoria' ORDER BY column_name", String.class))
        // escritorio_id veio com a 0039 (#228).
        .containsExactly("acao", "codigo_erro", "escritorio_id", "estado_antes",
            "estado_depois", "id", "ocorrido_em", "papel", "produtor_id", "recurso",
            "recurso_id", "sucesso", "usuario_id");
    assertThat(jdbc.queryForList("SELECT indexname FROM pg_indexes"
        + " WHERE tablename = 'registro_auditoria'", String.class))
        .contains("idx_registro_auditoria_produtor_ocorrido", "idx_registro_auditoria_ocorrido");

    String produtorId = UUID.randomUUID().toString();
    jdbc.update("INSERT INTO produtores (id, nome_completo) VALUES (?::uuid, ?)",
        produtorId, "Produtor Auditado");
    jdbc.update("INSERT INTO registro_auditoria (id, ocorrido_em, acao, recurso, produtor_id,"
        + " sucesso) VALUES (gen_random_uuid(), now(), 'NOTA_CRIADA', 'NOTA_FISCAL', ?::uuid,"
        + " TRUE)", produtorId);

    jdbc.update("DELETE FROM produtores WHERE id = ?::uuid", produtorId);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM registro_auditoria"
        + " WHERE produtor_id = ?::uuid", Integer.class, produtorId)).isOne();
  }

  @Test
  @DisplayName("changeSet 0028 cria alerta_tributario com chave unica e CASCADE do produtor (#140)")
  void changeSet0028CriaAlertaTributario() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns"
        + " WHERE table_name = 'alerta_tributario' ORDER BY column_name", String.class))
        .containsExactly("criado_em", "data_limite", "descricao", "id", "produtor_id",
            "referencia_id", "resolvido_em", "status", "tipo");
    assertThat(jdbc.queryForList("SELECT indexname FROM pg_indexes"
        + " WHERE tablename = 'alerta_tributario'", String.class))
        .contains("idx_alerta_tributario_produtor", "uk_alerta_tributario_referencia");

    String produtorId = UUID.randomUUID().toString();
    jdbc.update("INSERT INTO produtores (id, nome_completo) VALUES (?::uuid, ?)",
        produtorId, "Produtor Alerta");
    String insert = "INSERT INTO alerta_tributario (id, produtor_id, tipo, referencia_id,"
        + " data_limite, descricao, status, criado_em) VALUES (?::uuid, ?::uuid,"
        + " 'VENCIMENTO_GUIA', '44444444-4444-4444-4444-444444444444'::uuid, DATE '2026-10-30',"
        + " 'Guia', 'ATIVO', now())";
    jdbc.update(insert, UUID.randomUUID().toString(), produtorId);
    // Mesmo tipo, referencia e prazo: o job nunca duplica, nem com duas replicas.
    assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID().toString(), produtorId))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

    // Alerta e derivado: apagar o produtor apaga os alertas.
    jdbc.update("DELETE FROM produtores WHERE id = ?::uuid", produtorId);
    assertThat(jdbc.queryForObject(
        "SELECT COUNT(*) FROM alerta_tributario WHERE produtor_id = ?::uuid",
        Integer.class, produtorId)).isZero();
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
        "chamadas_funcao", "criado_em", "desfecho", "desfecho_dados", "desfecho_legislacao",
        "funcoes", "id", "latencia_ms", "modelo", "pergunta_lei_reescrita", "produtor_id", "rota",
        "tamanho_pergunta", "tokens_entrada", "tokens_estimados", "tokens_saida",
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
