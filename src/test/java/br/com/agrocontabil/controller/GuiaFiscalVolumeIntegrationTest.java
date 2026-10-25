package br.com.agrocontabil.controller;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.agrocontabil.config.MockIdentidadeFilter;
import br.com.agrocontabil.model.GuiaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusGuiaFiscal;
import br.com.agrocontabil.model.TipoGuiaFiscal;
import br.com.agrocontabil.repository.ContadorRepository;
import br.com.agrocontabil.repository.GuiaFiscalRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.support.TestDatabaseCleaner;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Teste de integração HTTP da listagem paginada de guias e da janela do calendário (issue #103).
 *
 * <p>Pilha real (controller → service → repositório → H2) com o {@link MockIdentidadeFilter}
 * registrado à mão, como no {@code IdentidadeMultiUsuarioIntegrationTest}: cada requisição
 * declara o produtor pelo header {@code X-Usuario-Id}. O que se prova aqui é o contrato que o
 * Angular vê: corpo array, total no header, recorte temporal e os 400 no envelope.
 */
@SpringBootTest
@Import(TestDatabaseCleaner.class)
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
class GuiaFiscalVolumeIntegrationTest {

  private static final LocalDate HOJE = LocalDate.now(ZoneId.of("America/Sao_Paulo"));

  @Autowired private WebApplicationContext webApplicationContext;
  @Autowired private ProdutorRepository produtorRepository;
  @Autowired private ContadorRepository contadorRepository;
  @Autowired private GuiaFiscalRepository guiaFiscalRepository;
  @Autowired private TestDatabaseCleaner cleaner;

  private MockMvc mockMvc;
  private Produtor produtor;

  @BeforeEach
  void setUp() {
    cleaner.limparTudo();
    produtor = produtorRepository.save(
        Produtor.builder().nomeCompleto("Maria Agricultora").cpfCnpj("98765432100").build());
    mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
        .addFilters(new MockIdentidadeFilter(produtorRepository, contadorRepository))
        .build();
  }

  /**
   * O teste nao e transacional e o H2 e compartilhado entre contextos: sem esta limpeza as guias
   * commitadas aqui sobrevivem e barram, pela FK, o {@code deleteAll} de produtores da classe
   * seguinte.
   */
  @AfterEach
  void tearDown() {
    cleaner.limparTudo();
  }

  // -------------------------------------------------------------------------
  // GET /api/contador/guias
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("Guias paginadas: pagina pedida, ordem por vencimento desc e total no header")
  void guiasPaginadasComTotalNoHeader() throws Exception {
    guia(LocalDate.of(2026, 1, 20));
    guia(LocalDate.of(2026, 3, 20));
    guia(LocalDate.of(2026, 2, 20));
    guia(LocalDate.of(2026, 5, 20));
    guia(LocalDate.of(2026, 4, 20));

    mockMvc.perform(comoProdutor(get("/api/contador/guias"))
            .param("produtorId", produtor.getId().toString())
            .param("size", "2"))
        .andExpect(status().isOk())
        .andExpect(header().string(GuiaFiscalController.HEADER_TOTAL, "5"))
        .andExpect(jsonPath("$[*].dataVencimento", contains("2026-05-20", "2026-04-20")));

    // Ultima pagina: sobra uma guia, a mais antiga.
    mockMvc.perform(comoProdutor(get("/api/contador/guias"))
            .param("produtorId", produtor.getId().toString())
            .param("size", "2")
            .param("page", "2"))
        .andExpect(status().isOk())
        .andExpect(header().string(GuiaFiscalController.HEADER_TOTAL, "5"))
        .andExpect(jsonPath("$[*].dataVencimento", contains("2026-01-20")));
  }

  @Test
  @DisplayName("Guias sem page/size: 1a pagina de 100, corpo array (compativel com o Angular)")
  void guiasSemParametrosDevolvemPaginaPadrao() throws Exception {
    for (int i = 0; i < 101; i++) {
      guia(LocalDate.of(2020, 1, 1).plusDays(i));
    }

    mockMvc.perform(comoProdutor(get("/api/contador/guias"))
            .param("produtorId", produtor.getId().toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$", hasSize(100)))
        .andExpect(header().string(GuiaFiscalController.HEADER_TOTAL, "101"));
  }

  @Test
  @DisplayName("size acima de 200 retorna 400 no envelope errors[]")
  void tamanhoAcimaDoMaximoRetorna400() throws Exception {
    mockMvc.perform(comoProdutor(get("/api/contador/guias"))
            .param("produtorId", produtor.getId().toString())
            .param("size", "201"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.errors[0].field").value("size"));
  }

  // -------------------------------------------------------------------------
  // GET /api/calendario-fiscal/{produtorId}/vencimentos
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("Calendario sem de/ate recorta mes corrente +-12 meses: guia de 2 anos fica fora")
  void calendarioSemParametrosUsaJanelaPadrao() throws Exception {
    LocalDate dentro = HOJE.minusMonths(11);
    guia(dentro);
    guia(HOJE.minusYears(2));
    guia(HOJE.plusYears(2));

    mockMvc.perform(comoProdutor(vencimentos()))
        .andExpect(status().isOk())
        // So as guias: o fechamento da apuracao (#181) tambem entra no calendario.
        .andExpect(jsonPath("$[?(@.tipo == 'imposto')]", hasSize(1)))
        .andExpect(jsonPath("$[?(@.tipo == 'imposto')].dataVencimento",
            contains(dentro.toString())));
  }

  @Test
  @DisplayName("Calendario com de/ate devolve so a janela pedida, extremos inclusivos")
  void calendarioComJanelaInformada() throws Exception {
    guia(LocalDate.of(2024, 6, 30));
    guia(LocalDate.of(2024, 7, 1));
    guia(LocalDate.of(2024, 9, 30));
    guia(LocalDate.of(2024, 10, 1));

    mockMvc.perform(comoProdutor(vencimentos())
            .param("de", "2024-07-01")
            .param("ate", "2024-09-30"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.tipo == 'imposto')].dataVencimento",
            contains("2024-09-30", "2024-07-01")));
  }

  @Test
  @DisplayName("Calendario com janela acima de 36 meses retorna 400 JANELA_CALENDARIO_INVALIDA")
  void calendarioComJanelaAcimaDoMaximoRetorna400() throws Exception {
    mockMvc.perform(comoProdutor(vencimentos())
            .param("de", "2020-01-01")
            .param("ate", "2026-12-31"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].code").value("JANELA_CALENDARIO_INVALIDA"))
        .andExpect(jsonPath("$.errors[0].field").value("ate"));
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private MockHttpServletRequestBuilder vencimentos() {
    return get("/api/calendario-fiscal/{produtorId}/vencimentos", produtor.getId());
  }

  private MockHttpServletRequestBuilder comoProdutor(MockHttpServletRequestBuilder requisicao) {
    return requisicao.header(MockIdentidadeFilter.HEADER_USUARIO_ID, produtor.getId());
  }

  private void guia(LocalDate vencimento) {
    guiaFiscalRepository.save(GuiaFiscal.builder()
        .produtor(produtor)
        .tipoGuia(TipoGuiaFiscal.ICMS)
        .dataVencimento(vencimento)
        .valor(new BigDecimal("100.00"))
        .status(StatusGuiaFiscal.PENDENTE)
        .build());
  }
}
