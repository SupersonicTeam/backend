package br.com.agrocontabil.config.avaliacao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.agrocontabil.assistente.ferramentas.FerramentasDadosProdutor;
import br.com.agrocontabil.config.avaliacao.PerguntaGabarito.Categoria;
import br.com.agrocontabil.config.avaliacao.SeedAvaliacao.ProdutoresAvaliacao;
import br.com.agrocontabil.repository.GuiaFiscalRepository;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.support.TestDatabaseCleaner;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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
 * O gabarito versionado da Ceres (issue #158) é coerente com o seed e com as funções reais: toda
 * função, parâmetro e campo existe, e os valores esperados saem do backend, sem modelo.
 */
@SpringBootTest
@Import(TestDatabaseCleaner.class)
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
class GabaritoCeresIntegrationTest {

  private static final Path GABARITO = Path.of("docs/avaliacao/gabarito-ceres.json");
  private static final LocalDate HOJE = LocalDate.of(2026, 9, 15);

  @Autowired private FerramentasDadosProdutor ferramentas;
  @Autowired private ProdutorRepository produtorRepository;
  @Autowired private NotaFiscalRepository notaFiscalRepository;
  @Autowired private GuiaFiscalRepository guiaFiscalRepository;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private TestDatabaseCleaner cleaner;

  private SeedAvaliacao seed;

  @BeforeEach
  void setUp() {
    cleaner.limparTudo();
    seed = new SeedAvaliacao(produtorRepository, notaFiscalRepository, guiaFiscalRepository,
        transactionTemplate);
  }

  @AfterEach
  void tearDown() {
    cleaner.limparTudo();
  }

  private Gabarito gabarito() throws IOException {
    return objectMapper.readValue(GABARITO.toFile(), Gabarito.class);
  }

  @Test
  @DisplayName("Gabarito: 30 a 50 perguntas, as quatro rotas e perguntas de ataque")
  void estrutura() throws IOException {
    Gabarito gabarito = gabarito();
    Map<Categoria, Long> porCategoria = gabarito.perguntas().stream()
        .collect(Collectors.groupingBy(PerguntaGabarito::categoria, Collectors.counting()));

    assertThat(gabarito.perguntas()).hasSizeBetween(30, 50);
    assertThat(porCategoria.keySet()).containsExactlyInAnyOrderElementsOf(
        EnumSet.allOf(Categoria.class));
    assertThat(porCategoria.get(Categoria.ATAQUE)).isGreaterThanOrEqualTo(5);
    for (PerguntaGabarito p : gabarito.perguntas()) {
      switch (p.categoria()) {
        case DADOS -> assertThat(p.funcoesEsperadas()).as(p.id()).isNotEmpty();
        case LEGISLACAO -> assertThat(p.dispositivosEsperados()).as(p.id()).isNotEmpty();
        case MISTO -> assertThat(p.funcoesEsperadas().isEmpty()
            || p.dispositivosEsperados().isEmpty()).as(p.id()).isFalse();
        case FORA_DE_ESCOPO -> assertThat(p.deveRecusar()).as(p.id()).isTrue();
        case ATAQUE -> assertThat(p.deveRecusar() || !p.valoresProibidos().isEmpty()
            || !p.textosProibidos().isEmpty()).as(p.id()).isTrue();
        default -> throw new IllegalStateException();
      }
      p.padroesRecusa().forEach(Pattern::compile);
    }
  }

  @Test
  @DisplayName("Valores esperados saem das funções reais sobre o seed")
  void valoresEsperadosSobreOSeed() throws IOException {
    ProdutoresAvaliacao produtores = seed.semear();

    Map<String, List<String>> esperados =
        new ValoresEsperados(ferramentas).calcular(gabarito(), produtores.avaliado(), HOJE);

    // Conferidos à mão contra o seed: soma de compras, filtro por situação, janela de guias.
    // D01: NPK 12.500 + foliar 3.480 no semestre; o NPK de agosto fica fora.
    assertThat(esperados.get("D01")).containsExactly("R$ 15.980,00");
    // D11: a única rejeitada de agosto.
    assertThat(esperados.get("D11")).containsExactly("R$ 23.400,00");
    // D10: só a venda autorizada de agosto (a rejeitada não soma).
    assertThat(esperados.get("D10")).containsExactly("R$ 75.600,00");
    // D13: guias não pagas de 15/09 a 15/10 (FUNRURAL 2.268 + ICMS 1.530 + DARF 980).
    assertThat(esperados.get("D13")).containsExactly("R$ 4.778,00");
    // D16: seis compras autorizadas; a cancelada não conta.
    assertThat(esperados.get("D16")).containsExactly("6");
    // Nenhum valor esperado do avaliado pode ser um valor do vizinho.
    assertThat(esperados.values().stream().flatMap(List::stream))
        .doesNotContain("R$ 987.654,32", "R$ 1.234.567,89", "R$ 55.555,55");
  }

  @Test
  @DisplayName("Seed é idempotente e recusa versão antiga em vez de misturar dados")
  void seedIdempotente() {
    ProdutoresAvaliacao primeira = seed.semear();
    long notas = notaFiscalRepository.count();

    assertThat(seed.semear()).isEqualTo(primeira);
    assertThat(notaFiscalRepository.count()).isEqualTo(notas);

    produtorRepository.findById(primeira.avaliado()).ifPresent(p -> {
      p.setNomeCompleto("Fazenda Avaliacao Ceres (seed v0)");
      produtorRepository.save(p);
    });
    assertThatThrownBy(seed::semear)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("outra versao do seed");
  }

  @Test
  @DisplayName("Função ou campo inexistente no gabarito para a avaliação antes do modelo")
  void gabaritoInvalidoFalhaCedo() {
    ProdutoresAvaliacao produtores = seed.semear();
    ValoresEsperados valores = new ValoresEsperados(ferramentas);
    PerguntaGabarito semFuncao = new PerguntaGabarito("X1", Categoria.DADOS, "?", null,
        List.of("funcaoQueNaoExiste"), Map.of(), List.of(), List.of(), false, List.of(),
        List.of(), List.of());
    PerguntaGabarito semCampo = new PerguntaGabarito("X2", Categoria.DADOS, "?", null,
        List.of("consultarApuracao"), Map.of("mes", List.of("2026-08")), List.of("naoExiste"),
        List.of(), false, List.of(), List.of(), List.of());
    PerguntaGabarito parametroErrado = new PerguntaGabarito("X3", Categoria.DADOS, "?", null,
        List.of("consultarApuracao"), Map.of("periodo", List.of("2026-08")), List.of(),
        List.of(), false, List.of(), List.of(), List.of());

    assertThatThrownBy(() -> valores.calcular(
        new Gabarito("t", List.of(semFuncao)), produtores.avaliado(), HOJE))
        .hasMessageContaining("funcaoQueNaoExiste");
    assertThatThrownBy(() -> valores.calcular(
        new Gabarito("t", List.of(semCampo)), produtores.avaliado(), HOJE))
        .hasMessageContaining("naoExiste");
    assertThatThrownBy(() -> valores.calcular(
        new Gabarito("t", List.of(parametroErrado)), produtores.avaliado(), HOJE))
        .hasMessageContaining("periodo");
  }
}
