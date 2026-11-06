package br.com.agrocontabil.controller;

import static br.com.agrocontabil.support.DadosCeresFixture.saida;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.agrocontabil.assistente.porta.TratamentoProdutoPorta;
import br.com.agrocontabil.assistente.porta.TratamentoProdutoPorta.Produto;
import br.com.agrocontabil.assistente.porta.TratamentoProdutoPorta.TratamentoConsultado;
import br.com.agrocontabil.assistente.rag.BuscaTextualLegislacaoService;
import br.com.agrocontabil.assistente.rag.TrechoRecuperado;
import br.com.agrocontabil.config.LimiteCorpoAssistenteFilter;
import br.com.agrocontabil.config.MockIdentidadeFilter;
import br.com.agrocontabil.model.DesfechoInteracao;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.TipoProdutor;
import br.com.agrocontabil.model.TipoTitularCota;
import br.com.agrocontabil.model.UsoSemanalAssistente;
import br.com.agrocontabil.model.UsoSemanalAssistenteId;
import br.com.agrocontabil.repository.AssistenteInteracaoRepository;
import br.com.agrocontabil.repository.ContadorRepository;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.repository.UsoSemanalAssistenteRepository;
import br.com.agrocontabil.support.ChatModelSimulado;
import br.com.agrocontabil.support.TestDatabaseCleaner;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Atalho "O que muda para mim com o IBS e o CBS?" pela API (#347): com a base de legislação
 * simulada pelo texto real dos dispositivos (o H2 não tem a base) e a tabela oficial simulada, o
 * produtor pessoa física que vendeu soja recebe o texto explicado com o produto dele, o Funrural
 * e as citações — sem chamar o modelo e com a cota da semana esgotada.
 */
@SpringBootTest
@Import(TestDatabaseCleaner.class)
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "app.assistente.cota.habilitada=true",
    "app.assistente.cota.creditos-por-semana-produtor=100"})
@Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
class AtalhoReformaIntegrationTest {

  private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
  private static final Produto SOJA = new Produto("12019000", "Soja, mesmo triturada", 1,
      "515001", new BigDecimal("60.00"), true, null, "137", null, true);

  private static Map<String, String> lei;

  @Autowired private WebApplicationContext webApplicationContext;
  @Autowired private ProdutorRepository produtorRepository;
  @Autowired private ContadorRepository contadorRepository;
  @Autowired private NotaFiscalRepository notaFiscalRepository;
  @Autowired private UsoSemanalAssistenteRepository usoRepository;
  @Autowired private AssistenteInteracaoRepository interacaoRepository;
  @Autowired private LimiteCorpoAssistenteFilter limiteCorpoFilter;
  @Autowired private ChatModelSimulado modelo;
  @Autowired private TestDatabaseCleaner cleaner;
  @MockitoBean private BuscaTextualLegislacaoService busca;
  @MockitoBean private TratamentoProdutoPorta tratamento;

  private MockMvc mockMvc;
  private Produtor produtor;

  @BeforeAll
  static void carregarFixture() throws IOException {
    try (InputStream in = AtalhoReformaIntegrationTest.class
        .getResourceAsStream("/ceres/atalho-reforma-trechos.json")) {
      lei = new ObjectMapper().readValue(in, new TypeReference<Map<String, String>>() { });
    }
  }

  @BeforeEach
  void setUp() {
    cleaner.limparTudo();
    usoRepository.deleteAll();
    modelo.reiniciar();
    when(busca.buscarDispositivos(anyList(), any())).thenAnswer(inv -> {
      List<String> chaves = inv.getArgument(0);
      return chaves.stream().filter(lei::containsKey).map(chave -> {
        int barra = chave.indexOf('|');
        return new TrechoRecuperado(null, "id-" + chave, chave.substring(0, barra),
            chave.substring(barra + 1), "https://www.planalto.gov.br", lei.get(chave), 0);
      }).toList();
    });
    when(tratamento.consultar(eq("12019000"), any()))
        .thenReturn(new TratamentoConsultado("12019000", List.of(SOJA), "IT 2025.002 v1.70"));
    produtor = produtorRepository.save(Produtor.builder()
        .nomeCompleto("Fazenda Atalho").cpfCnpj("98765432100").estado("MT")
        .tipoProdutor(TipoProdutor.PESSOA_FISICA).build());
    LocalDate hoje = LocalDate.now(ZONE);
    notaFiscalRepository.save(saida(produtor,
        hoje.withDayOfYear(1).atTime(10, 0).atZone(ZONE).toOffsetDateTime(), "Soja em graos",
        "12019000", "75600.00"));
    mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
        .addFilters(limiteCorpoFilter,
            new MockIdentidadeFilter(produtorRepository, contadorRepository))
        .build();
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("Produtor pessoa fisica com venda de soja e a cota esgotada: 200 com o texto "
      + "explicado, o produto dele, o Funrural e as citacoes, sem chamar o modelo")
  void textoPersonalizadoSemModelo() throws Exception {
    usoRepository.save(UsoSemanalAssistente.builder()
        .id(idDaSemana()).creditosConsumidos(50).atualizadoEm(OffsetDateTime.now(ZONE)).build());

    mockMvc.perform(post("/api/assistente/chat")
            .header(MockIdentidadeFilter.HEADER_USUARIO_ID, produtor.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"pergunta\":\"O que muda para mim com o IBS e o CBS?\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rota").value("LEGISLACAO"))
        .andExpect(jsonPath("$.resposta").value(org.hamcrest.Matchers.startsWith(
            "O que muda para você com o IBS e a CBS")))
        .andExpect(jsonPath("$.resposta").value(org.hamcrest.Matchers.containsString(
            "Soja, mesmo triturada (NCM 1201.90.00): reducao de 60% do IBS e da CBS")))
        .andExpect(jsonPath("$.resposta").value(org.hamcrest.Matchers.containsString(
            "Lei 8.212/1991, art. 30, IV")))
        .andExpect(jsonPath("$.resposta").value(org.hamcrest.Matchers.containsString(
            "Procure um escritório de contabilidade")))
        .andExpect(jsonPath("$.citacoes[0].norma").value("LC 214/2025"))
        .andExpect(jsonPath("$.citacoes[0].dispositivo").value("art. 1º, inciso I"))
        .andExpect(jsonPath("$.citacoes[0].trecho").value(
            lei.get("LC 214/2025|art. 1º, inciso I")))
        .andExpect(jsonPath("$.citacoes[?(@.norma == 'Lei 8.212/1991')]").isNotEmpty())
        .andExpect(jsonPath("$.modoFallback").value(false))
        .andExpect(jsonPath("$.fontesDados").isEmpty());

    assertThat(modelo.promptsRecebidos()).isEmpty();
    assertThat(modelo.classificacoesRecebidas()).isEmpty();
    UsoSemanalAssistente uso = usoRepository.findById(idDaSemana()).orElseThrow();
    assertThat(uso.getCreditosConsumidos()).isEqualTo(50);
    assertThat(uso.getCreditosReservados()).isZero();
    assertThat(interacaoRepository.findByProdutorIdOrderByCriadoEmDesc(produtor.getId()))
        .singleElement().satisfies(i -> {
          assertThat(i.getDesfecho()).isEqualTo(DesfechoInteracao.RESPONDIDA);
          assertThat(i.getCamadaClassificacao()).isEqualTo("ATALHO_FIXO");
          assertThat(i.getTrechosCitados()).contains("id-LC 214/2025|art. 137");
        });
  }

  private UsoSemanalAssistenteId idDaSemana() {
    LocalDate segunda = LocalDate.now(ZONE).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    return new UsoSemanalAssistenteId(TipoTitularCota.PRODUTOR, produtor.getId(), segunda);
  }
}
