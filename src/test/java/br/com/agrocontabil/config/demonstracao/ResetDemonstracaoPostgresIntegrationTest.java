package br.com.agrocontabil.config.demonstracao;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.model.Contador;
import br.com.agrocontabil.model.Escritorio;
import br.com.agrocontabil.model.FuncaoEscritorio;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.RegimeTributario;
import br.com.agrocontabil.model.TipoProdutor;
import br.com.agrocontabil.repository.ContadorRepository;
import br.com.agrocontabil.repository.EscritorioRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
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
 * O procedimento de reset dos dados da demonstração ({@code docs/demonstracao/reset-*.sql},
 * descrito em {@code docs/demonstracao-banca.md}) contra o PostgreSQL 16 com o changelog inteiro:
 * seed → conferência → reset → seed de novo, com as contagens batendo e nada fora do seed tocado.
 *
 * <p>Os scripts rodados aqui são os mesmos arquivos que o Murillo roda na VPS (lidos do disco), e
 * as constantes que eles repetem (nome e CNPJ do escritório, nomes da carteira) são conferidas
 * contra o {@link CenarioDemonstracao}: mudou o seed, o teste pede o script novo.
 */
@SpringBootTest(properties = {
    "app.demonstracao.habilitada=true",
    "app.assistente.cota.habilitada=true",
    "app.seed.senha=Banca-AgroConIA-demonstracao-2026"})
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@DirtiesContext(classMode = ClassMode.AFTER_CLASS)
@DisplayName("Reset dos dados da demonstracao da banca no PostgreSQL")
// Sem TestDatabaseCleaner (CLAUDE.md, #90): o banco e um container proprio desta classe, descartado
// com o contexto (@DirtiesContext), e apagar produtores e justamente o que o teste prova.
class ResetDemonstracaoPostgresIntegrationTest {

  private static final Path CONFERENCIA = Path.of("docs/demonstracao/reset-01-conferencia.sql");
  private static final Path APAGAR = Path.of("docs/demonstracao/reset-02-apagar.sql");

  /** Tabelas que o reset nunca toca, mesmo com linha ligada ao seed. */
  private static final Set<String> FORA_DO_RESET = Set.of("legislacao_trecho", "shedlock",
      "databasechangelog", "databasechangeloglock", "registro_auditoria");

  @Container
  @SuppressWarnings("resource") // ciclo de vida gerenciado pela extensao @Testcontainers
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:0.8.6-pg16")
          .asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("agrocontabil_reset_test")
          .withUsername("agrocontabil")
          .withPassword("test");

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

  @Autowired private DataSource dataSource;
  @Autowired private SeedDemonstracao seed;
  @Autowired private CotaDemonstracaoCeres cotaCeres;
  @Autowired private ProdutorRepository produtorRepository;
  @Autowired private EscritorioRepository escritorioRepository;
  @Autowired private ContadorRepository contadorRepository;

  @Test
  @DisplayName("seed -> conferencia -> reset -> seed: contagens batem e nada fora do seed muda")
  void resetERessemeadura() throws Exception {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    Map<String, Long> primeira = conferencia(jdbc);
    assertThat(primeira).containsEntry("escritorio", 1L)
        .containsEntry("contadores", (long) CenarioDemonstracao.EQUIPE.size())
        .containsEntry("produtores", (long) CenarioDemonstracao.CARTEIRA.size())
        .containsEntry("credencial_usuario",
            (long) (CenarioDemonstracao.EQUIPE.size() + CenarioDemonstracao.CARTEIRA.size()))
        .containsEntry("carteira_vinculo", (long) CenarioDemonstracao.CARTEIRA.size())
        .containsEntry("modelo_pedido", (long) CenarioDemonstracao.MODELOS_PEDIDO.size())
        .containsEntry("contadores_fora_da_equipe", 0L)
        .containsEntry("atencao_titular_anonimizado", 0L);
    assertThat(primeira.get("nota_fiscal")).isGreaterThan(100L);
    assertThat(primeira.get("nota_fiscal_item")).isGreaterThan(100L);

    // Ensaio: a assistente foi removida da equipe (sem escritorio, como na tela) e um contador de
    // fora entrou no escritorio por convite. Dado que nao e do seed: homonimo de um cliente sem
    // vinculo e escritorio de mesmo nome com outro CNPJ.
    jdbc.update("UPDATE contadores SET escritorio_id = NULL, funcao_escritorio = NULL"
        + " WHERE nome_completo = ?", CenarioDemonstracao.EQUIPE.get(2).nome());
    UUID escritorioSeed = jdbc.queryForObject("SELECT id FROM escritorio WHERE cnpj = ?",
        UUID.class, CenarioDemonstracao.ESCRITORIO_CNPJ);
    Contador convidado = contadorRepository.save(Contador.builder()
        .nomeCompleto("Avaliador Convidado da Banca").cpfCnpj(CenarioDemonstracao.cpf("222333444"))
        .escritorioId(escritorioSeed).funcao(FuncaoEscritorio.CONTADOR).build());
    Produtor homonimo = produtorRepository.save(Produtor.builder()
        .nomeCompleto("Luiz Carlos Bertoldo").cpfCnpj(CenarioDemonstracao.cpf("111222333"))
        .tipoProdutor(TipoProdutor.PESSOA_FISICA).regimeTributario(RegimeTributario.REAL)
        .estado("PR").build());
    Escritorio outro = escritorioRepository.save(Escritorio.builder()
        .nome(CenarioDemonstracao.ESCRITORIO_NOME).cnpj(CenarioDemonstracao.cnpj("111222333000"))
        .build());
    // No ensaio, a administradora trocou o endereco (#464): o antigo ficou em carencia.
    jdbc.update("UPDATE escritorio SET slug = 'rural-oeste-ensaio' WHERE id = ?", escritorioSeed);
    jdbc.update("INSERT INTO escritorio_slug_anterior (slug, escritorio_id, liberado_em)"
        + " VALUES (?, ?, now() + interval '90 days')", CenarioDemonstracao.ESCRITORIO_SLUG,
        escritorioSeed);

    Map<String, Long> aApagar = conferencia(jdbc);
    assertThat(aApagar).containsEntry("contadores", (long) CenarioDemonstracao.EQUIPE.size())
        .containsEntry("contadores_fora_da_equipe", 1L)
        .containsEntry("escritorio_slug_anterior", 1L);
    Map<String, Long> antes = totais(jdbc);
    executar(APAGAR);
    Map<String, Long> depois = totais(jdbc);

    assertThat(conferencia(jdbc).values()).as("nada do seed sobra").containsOnly(0L);
    for (Map.Entry<String, Long> tabela : antes.entrySet()) {
      long apagado = aApagar.getOrDefault(tabela.getKey(), 0L);
      assertThat(depois.get(tabela.getKey())).as(tabela.getKey())
          .isEqualTo(tabela.getValue() - apagado);
      if (FORA_DO_RESET.contains(tabela.getKey())) {
        assertThat(depois.get(tabela.getKey())).as(tabela.getKey()).isEqualTo(tabela.getValue());
      }
    }
    assertThat(produtorRepository.findById(homonimo.getId())).isPresent();
    assertThat(escritorioRepository.findById(outro.getId())).isPresent();
    assertThat(contadorRepository.findById(convidado.getId())).hasValueSatisfying(c -> {
      assertThat(c.getEscritorioId()).isNull();
      assertThat(c.getFuncao()).isNull();
    });

    // Subir o backend = o runner do seed de novo. Chamado direto, para a excecao aparecer.
    assertThat(seed.jaSemeado()).isFalse();
    SeedDemonstracao.Resumo resumo = seed.semear();
    cotaCeres.prepararSemana(); // como o run(): a cota da Ceres no limite
    assertThat(resumo.notasRecusadas()).isZero();
    assertThat(conferencia(jdbc)).isEqualTo(primeira);
    // O endereco da banca volta, e a carencia do ensaio nao sobrou (#464).
    assertThat(jdbc.queryForObject("SELECT slug FROM escritorio WHERE cnpj = ?", String.class,
        CenarioDemonstracao.ESCRITORIO_CNPJ)).isEqualTo(CenarioDemonstracao.ESCRITORIO_SLUG);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM escritorio_slug_anterior", Long.class))
        .isZero();
    // O leite UHT do Luiz vai ao supermercado, nao ao laticinio (#284).
    assertThat(jdbc.queryForList("SELECT DISTINCT n.destinatario_nome FROM nota_fiscal n"
            + " JOIN nota_fiscal_item i ON i.nota_fiscal_id = n.id"
            + " WHERE i.descricao = 'Leite UHT integral, caixa de 1 litro'", String.class))
        .containsExactly("Supermercados Bom Dia Oeste Ltda");
  }

  private static Map<String, Long> conferencia(JdbcTemplate jdbc) throws IOException {
    Map<String, Long> contagem = new LinkedHashMap<>();
    jdbc.query(Files.readString(CONFERENCIA, StandardCharsets.UTF_8),
        rs -> {
          contagem.put(rs.getString("tabela"), rs.getLong("linhas"));
        });
    return contagem;
  }

  /** Linhas de toda tabela do schema público. */
  private static Map<String, Long> totais(JdbcTemplate jdbc) {
    Map<String, Long> totais = new LinkedHashMap<>();
    jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema ="
            + " 'public' AND table_type = 'BASE TABLE' ORDER BY table_name", String.class)
        .forEach(t -> totais.put(t,
            jdbc.queryForObject("SELECT COUNT(*) FROM \"" + t + "\"", Long.class)));
    return totais;
  }

  private void executar(Path script) throws SQLException {
    try (Connection conexao = dataSource.getConnection()) {
      ScriptUtils.executeSqlScript(conexao,
          new EncodedResource(new FileSystemResource(script), StandardCharsets.UTF_8));
    }
  }
}
