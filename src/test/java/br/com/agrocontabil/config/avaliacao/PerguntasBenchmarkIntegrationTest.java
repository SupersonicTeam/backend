package br.com.agrocontabil.config.avaliacao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import br.com.agrocontabil.assistente.ferramentas.FerramentasDadosProdutor;
import br.com.agrocontabil.config.avaliacao.SeedAvaliacao.ProdutoresAvaliacao;
import br.com.agrocontabil.repository.GuiaFiscalRepository;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import br.com.agrocontabil.repository.NotaFiscalRevisaoRepository;
import br.com.agrocontabil.repository.NotaFiscalValidacaoRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.support.TestDatabaseCleaner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * O conjunto congelado do benchmark da banca (#352) é lido pelo comando de avaliação como um
 * gabarito: 12 temas de 6 a 10 perguntas, hash ao lado conferido, as perguntas herdadas iguais às
 * do gabarito e os valores esperados saindo das funções reais sobre o seed, sem modelo.
 */
@SpringBootTest
@Import(TestDatabaseCleaner.class)
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
class PerguntasBenchmarkIntegrationTest {

  static final Path PERGUNTAS = Path.of("docs/avaliacao/benchmark/perguntas-benchmark.json");
  private static final Path HASH = Path.of("docs/avaliacao/benchmark/perguntas-benchmark.json.sha256");
  private static final Path GABARITO = Path.of("docs/avaliacao/gabarito-ceres.json");
  private static final LocalDate HOJE = LocalDate.of(2026, 9, 15);
  /** Campos que só o benchmark tem: o resto de uma pergunta herdada é igual ao do gabarito. */
  private static final Set<String> CAMPOS_DO_BENCHMARK = Set.of("tema", "capacidade");

  @Autowired private FerramentasDadosProdutor ferramentas;
  @Autowired private ProdutorRepository produtorRepository;
  @Autowired private NotaFiscalRepository notaFiscalRepository;
  @Autowired private GuiaFiscalRepository guiaFiscalRepository;
  @Autowired private NotaFiscalRevisaoRepository revisaoRepository;
  @Autowired private NotaFiscalValidacaoRepository validacaoRepository;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private TestDatabaseCleaner cleaner;

  @BeforeEach
  void setUp() {
    cleaner.limparTudo();
  }

  @AfterEach
  void tearDown() {
    cleaner.limparTudo();
  }

  @Test
  @DisplayName("Hash ao lado confere: mudar o conjunto exige mudar a versão e o .sha256")
  void hashConfere() throws IOException, NoSuchAlgorithmException {
    String esperado = Files.readString(HASH, StandardCharsets.UTF_8).split("\\s+")[0];
    byte[] bytes = Files.readAllBytes(PERGUNTAS);
    assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
        .isEqualTo(esperado);
  }

  @Test
  @DisplayName("Cerca de 100 perguntas, 12 temas de 6 a 10, tema e capacidade em todas")
  void estrutura() throws IOException {
    JsonNode raiz = objectMapper.readTree(PERGUNTAS.toFile());
    Set<String> temas = new HashSet<>();
    raiz.path("temas").fieldNames().forEachRemaining(temas::add);
    Map<String, Integer> porTema = new HashMap<>();
    Set<String> ids = new HashSet<>();
    for (JsonNode p : raiz.path("perguntas")) {
      String id = p.path("id").asText();
      assertThat(ids.add(id)).as("id repetido: " + id).isTrue();
      assertThat(temas).as(id).contains(p.path("tema").asText());
      assertThat(p.path("capacidade").asText()).as(id).isIn("comum", "so_ceres_2");
      porTema.merge(p.path("tema").asText(), 1, Integer::sum);
    }
    assertThat(ids).hasSizeBetween(95, 105);
    assertThat(porTema.keySet()).isEqualTo(temas).hasSize(12);
    assertThat(porTema.values()).allSatisfy(n -> assertThat(n).isBetween(6, 10));
  }

  @Test
  @DisplayName("Pergunta herdada do gabarito não diverge dele enquanto ele estiver na versão de baseadoEm")
  void herdadasIguaisAoGabarito() throws IOException {
    JsonNode raizGabarito = objectMapper.readTree(GABARITO.toFile());
    // O conjunto e congelado: depois que o gabarito muda de versao, ele deixa de acompanhar (a
    // conferencia so vale enquanto o gabarito e o mesmo de "baseadoEm").
    assumeTrue(raizGabarito.path("versao").asText().equals(
        objectMapper.readTree(PERGUNTAS.toFile()).path("baseadoEm").asText()),
        "gabarito em outra versao: o conjunto congelado nao o acompanha");
    Map<String, JsonNode> gabarito = new HashMap<>();
    raizGabarito.path("perguntas").forEach(p -> gabarito.put(p.path("id").asText(), p));
    int herdadas = 0;
    for (JsonNode p : objectMapper.readTree(PERGUNTAS.toFile()).path("perguntas")) {
      JsonNode original = gabarito.get(p.path("id").asText());
      if (original == null) {
        continue;
      }
      herdadas++;
      ObjectNode semCamposNovos = ((ObjectNode) p.deepCopy()).without(CAMPOS_DO_BENCHMARK);
      assertThat(semCamposNovos).as(p.path("id").asText()).isEqualTo(original);
    }
    assertThat(herdadas).isEqualTo(gabarito.size());
  }

  @Test
  @DisplayName("Valores esperados saem das funções reais sobre o seed, inclusive os das novas")
  void valoresEsperadosSobreOSeed() throws IOException {
    SeedAvaliacao seed = new SeedAvaliacao(produtorRepository, notaFiscalRepository,
        guiaFiscalRepository, revisaoRepository, validacaoRepository, transactionTemplate);
    ProdutoresAvaliacao produtores = seed.semear();
    Gabarito perguntas = objectMapper.readValue(PERGUNTAS.toFile(), Gabarito.class);

    Map<String, List<String>> esperados =
        new ValoresEsperados(ferramentas).calcular(perguntas, produtores.avaliado(), HOJE);

    // Conferidos à mão contra o seed (SeedAvaliacao v4).
    // N01: o herbicida glifosato de abril.
    assertThat(esperados.get("N01")).containsExactly("R$ 8.250,00");
    // N02 e N10: os três fertilizantes de 2026 (NPK de janeiro e agosto, foliar de março).
    assertThat(esperados.get("N02")).containsExactly("3");
    assertThat(esperados.get("N10")).containsExactly("R$ 25.130,00");
    // N03: NCM 3105 = os mesmos três (31052000 e 31059090).
    assertThat(esperados.get("N03")).containsExactly("R$ 25.130,00");
    // N04: só o milho autorizado de junho (o rejeitado e o devolvido não somam).
    assertThat(esperados.get("N04")).containsExactly("R$ 108.900,00");
    // N05: a venda de junho; N06: soja de março + milho de junho.
    assertThat(esperados.get("N05")).containsExactly("R$ 108.900,00");
    assertThat(esperados.get("N06")).containsExactly("R$ 252.900,00");
    // N11: a venda autorizada de agosto, a mesma da D10.
    assertThat(esperados.get("N11")).isEqualTo(esperados.get("D10"));
    // N08 é a T07 dita do jeito do produtor: o mesmo valor.
    assertThat(esperados.get("N08")).isEqualTo(esperados.get("T07"));
    assertThat(esperados).containsKeys("N07", "N09");
    // Nenhum valor esperado do avaliado pode ser um valor do vizinho.
    assertThat(esperados.values().stream().flatMap(List::stream))
        .doesNotContain("R$ 987.654,32", "R$ 1.234.567,89", "R$ 55.555,55");
  }
}
