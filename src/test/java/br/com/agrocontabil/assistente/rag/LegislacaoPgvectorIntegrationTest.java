package br.com.agrocontabil.assistente.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.agrocontabil.config.AssistenteProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
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
 * RAG contra pgvector de verdade (issue #156): changelog aplicado pelo Liquibase, ingestão pelo
 * {@code PgVectorStore} com o {@code EmbeddingModelSimulado} (1024 dimensões) e busca por
 * similaridade com filtro de vigência. Ignorado sem Docker; roda na CI.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@DirtiesContext(classMode = ClassMode.AFTER_CLASS)
class LegislacaoPgvectorIntegrationTest {

  private static final String URL = "https://www.planalto.gov.br/ccivil_03/leis/lcp/lcp214.htm";
  private static final LocalDate PUBLICACAO = LocalDate.of(2025, 1, 16);

  @Container
  @SuppressWarnings("resource") // ciclo de vida gerenciado pela extensao @Testcontainers
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
      DockerImageName.parse("pgvector/pgvector:0.8.6-pg16").asCompatibleSubstituteFor("postgres"))
      .withDatabaseName("agrocontabil_rag_test")
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
    // O embedding simulado e de saco de palavras: similaridades menores que as do bge-m3.
    registry.add("app.assistente.rag.similaridade-minima", () -> "0.2");
  }

  @Autowired private FatiadorDispositivos fatiador;
  @Autowired private IngestaoLegislacaoService ingestao;
  @Autowired private BuscaLegislacaoService busca;
  @Autowired private DataSource dataSource;
  @Autowired private AssistenteProperties propriedades;

  private List<DispositivoLegal> dispositivos;

  @BeforeEach
  void setUp() throws IOException {
    new JdbcTemplate(dataSource).update("DELETE FROM legislacao_trecho");
    dispositivos = fatiador.fatiar(new ClassPathResource("legislacao/norma-ilustrativa.html")
        .getContentAsString(StandardCharsets.UTF_8));
  }

  @Test
  @DisplayName("DoD #156: rodar a ingestao duas vezes nao duplica trechos")
  void ingestaoIdempotente() {
    FonteLegislacao fonte = new FonteLegislacao("LC 214/2025", URL, PUBLICACAO, PUBLICACAO);

    ResumoIngestao primeira = ingestao.ingerir(fonte, dispositivos);
    ResumoIngestao segunda = ingestao.ingerir(fonte, dispositivos);

    assertThat(primeira.novos()).isEqualTo(dispositivos.size());
    assertThat(segunda.novos()).isZero();
    assertThat(segunda.inalterados()).isEqualTo(dispositivos.size());
    assertThat(contar()).isEqualTo(dispositivos.size());
  }

  @Test
  @DisplayName("Nova redacao entra como nova vigencia; a anterior so tem o fim fechado")
  void novaRedacaoNovaVigencia() {
    FonteLegislacao original = new FonteLegislacao("LC 214/2025", URL, PUBLICACAO, PUBLICACAO);
    ingestao.ingerir(original, dispositivos);

    LocalDate alteracao = LocalDate.of(2026, 7, 1);
    List<DispositivoLegal> alterados = dispositivos.stream()
        .map(d -> d.dispositivo().equals("art. 1º")
            ? new DispositivoLegal(d.dispositivo(), d.texto() + " Alterado.")
            : d)
        .toList();
    ResumoIngestao resumo = ingestao.ingerir(
        new FonteLegislacao("LC 214/2025", URL, alteracao, alteracao), alterados);

    assertThat(resumo.novos()).isEqualTo(1);
    assertThat(resumo.vigenciasFechadas()).isEqualTo(1);
    List<String> vigencias = new JdbcTemplate(dataSource).queryForList(
        "SELECT vigencia_inicio || '/' || vigencia_fim FROM legislacao_trecho"
            + " WHERE dispositivo = 'art. 1º' ORDER BY vigencia_inicio", String.class);
    assertThat(vigencias).containsExactly("2025-01-16/2026-06-30", "2026-07-01/9999-12-31");
  }

  @Test
  @DisplayName("Texto alterado sem vigencia posterior e recusado antes de inserir qualquer trecho")
  void vigenciaNaoPosteriorRecusada() {
    FonteLegislacao original = new FonteLegislacao("LC 214/2025", URL, PUBLICACAO, PUBLICACAO);
    ingestao.ingerir(original, dispositivos);
    List<DispositivoLegal> alterados = dispositivos.stream()
        .map(d -> d.dispositivo().equals("art. 1º")
            ? new DispositivoLegal(d.dispositivo(), d.texto() + " Alterado.")
            : d)
        .toList();

    // Recaptura com a vigencia-inicio esquecida (igual a da redacao vigente).
    assertThatThrownBy(() -> ingestao.ingerir(original, alterados))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("art. 1º");
    assertThat(contar()).isEqualTo(dispositivos.size());
  }

  @Test
  @DisplayName("Busca devolve so a redacao vigente na data da pergunta, com metadados")
  void buscaPorVigencia() {
    ingestao.ingerir(new FonteLegislacao("LC 214/2025", URL, PUBLICACAO, PUBLICACAO),
        dispositivos);
    LocalDate alteracao = LocalDate.of(2026, 7, 1);
    ingestao.ingerir(new FonteLegislacao("LC 214/2025", URL, alteracao, alteracao),
        List.of(new DispositivoLegal("art. 1º",
            "Art. 1º Esta norma institui o tributo de exemplo sobre operacoes com bens e servicos.")));

    List<TrechoRecuperado> antes = busca.buscar("tributo de exemplo operacoes com bens",
        LocalDate.of(2026, 1, 10));
    List<TrechoRecuperado> depois = busca.buscar("tributo de exemplo operacoes com bens",
        LocalDate.of(2026, 9, 29));

    assertThat(antes).isNotEmpty().allSatisfy(t -> assertThat(t.url()).isEqualTo(URL));
    assertThat(textoDoArt1(antes)).doesNotContain("servicos");
    assertThat(textoDoArt1(depois)).contains("servicos");
    assertThat(depois.get(0).marca()).isEqualTo("T1");
    assertThat(depois.size()).isLessThanOrEqualTo(propriedades.getRag().getTopK());
  }

  @Test
  @DisplayName("Pergunta sem relacao com a lei: nenhum trecho acima do limiar")
  void semRelacaoSemTrecho() {
    ingestao.ingerir(new FonteLegislacao("LC 214/2025", URL, PUBLICACAO, PUBLICACAO),
        dispositivos);

    // Sem nenhuma palavra em comum com a norma: o embedding simulado e de saco de palavras, e uma
    // preposicao compartilhada ("de") ja o aproximaria.
    assertThat(busca.buscar("futebol campeonato brasileiro final", LocalDate.of(2026, 9, 29)))
        .isEmpty();
  }

  private String textoDoArt1(List<TrechoRecuperado> trechos) {
    return trechos.stream().filter(t -> t.dispositivo().equals("art. 1º"))
        .map(TrechoRecuperado::texto).findFirst().orElse("");
  }

  private int contar() {
    return new JdbcTemplate(dataSource).queryForObject(
        "SELECT COUNT(*) FROM legislacao_trecho", Integer.class);
  }
}
