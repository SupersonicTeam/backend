package br.com.agrocontabil.controller;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.agrocontabil.config.MockIdentidadeFilter;
import br.com.agrocontabil.model.Contador;
import br.com.agrocontabil.model.Escritorio;
import br.com.agrocontabil.model.FuncaoEscritorio;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.SituacaoVinculo;
import br.com.agrocontabil.model.VinculoCarteira;
import br.com.agrocontabil.repository.ContadorRepository;
import br.com.agrocontabil.repository.EscritorioRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.repository.VinculoCarteiraRepository;
import br.com.agrocontabil.support.TestDatabaseCleaner;
import java.time.OffsetDateTime;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * {@code GET /api/vinculos?situacao=} (issue #353): o produtor vê também os escritórios que já o
 * atenderam. O padrão (sem parâmetro) continua ativos e suspensos, para o frontend de hoje não
 * mudar, e o recorte é sempre o do próprio produtor (dois produtores no cenário).
 */
@SpringBootTest
@Import(TestDatabaseCleaner.class)
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
@DisplayName("Historico de vinculos do produtor (#353)")
class VinculosHistoricoIntegrationTest {

  private static final String VINCULOS = "/api/vinculos";
  private static final OffsetDateTime BASE = OffsetDateTime.parse("2026-01-10T10:00:00-03:00");

  @Autowired private WebApplicationContext webApplicationContext;
  @Autowired private ProdutorRepository produtorRepository;
  @Autowired private ContadorRepository contadorRepository;
  @Autowired private EscritorioRepository escritorioRepository;
  @Autowired private VinculoCarteiraRepository vinculoRepository;
  @Autowired private TestDatabaseCleaner cleaner;

  private MockMvc mockMvc;
  private Produtor produtorA;
  private Produtor produtorB;
  private Contador contador;

  @BeforeEach
  void setUp() {
    cleaner.limparTudo();
    produtorA = produtorRepository.save(
        Produtor.builder().nomeCompleto("Produtor A").cpfCnpj("52998224725").build());
    produtorB = produtorRepository.save(
        Produtor.builder().nomeCompleto("Produtor B").cpfCnpj("11144477735").build());
    Escritorio ativo = escritorio("Escritorio Ativo");
    Escritorio suspenso = escritorio("Escritorio Suspenso");
    Escritorio antigo = escritorio("Escritorio Antigo");
    Escritorio maisRecente = escritorio("Escritorio Mais Recente");
    Escritorio doOutro = escritorio("Escritorio do B");
    contador = contadorRepository.save(Contador.builder().escritorioId(ativo.getId())
        .funcao(FuncaoEscritorio.ADMINISTRADOR).nomeCompleto("Contador").cpfCnpj("12345678000195")
        .build());

    vincular(ativo, produtorA, SituacaoVinculo.ATIVO, BASE.plusMonths(6), null);
    vincular(suspenso, produtorA, SituacaoVinculo.SUSPENSO, BASE.plusMonths(4), null);
    // O que terminou por ultimo nao e o que comecou por ultimo.
    vincular(antigo, produtorA, SituacaoVinculo.ENCERRADO, BASE, BASE.plusMonths(8));
    vincular(maisRecente, produtorA, SituacaoVinculo.ENCERRADO, BASE.plusMonths(2),
        BASE.plusMonths(3));
    // Do outro produtor: nunca aparece para A.
    vincular(doOutro, produtorB, SituacaoVinculo.ENCERRADO, BASE, BASE.plusMonths(1));
    vincular(ativo, produtorB, SituacaoVinculo.ATIVO, BASE, null);

    mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
        .addFilters(new MockIdentidadeFilter(produtorRepository, contadorRepository))
        .build();
  }

  @AfterEach
  void tearDown() {
    cleaner.limparTudo();
  }

  @Test
  @DisplayName("sem parametro: ativos e suspensos, como sempre; o encerrado nao aparece")
  void padraoNaoMudou() throws Exception {
    mockMvc.perform(como(produtorA, get(VINCULOS)))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Total-Count", "2"))
        .andExpect(jsonPath("$[*].escritorio",
            contains("Escritorio Ativo", "Escritorio Suspenso")))
        .andExpect(jsonPath("$[*].situacao", contains("ATIVO", "SUSPENSO")));
  }

  @Test
  @DisplayName("situacao=ENCERRADO: so os encerrados, com fim, do que terminou por ultimo ao primeiro")
  void soEncerrados() throws Exception {
    mockMvc.perform(como(produtorA, get(VINCULOS).param("situacao", "ENCERRADO")))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Total-Count", "2"))
        .andExpect(jsonPath("$[*].escritorio",
            contains("Escritorio Antigo", "Escritorio Mais Recente")))
        .andExpect(jsonPath("$[*].situacao", everyItem(org.hamcrest.Matchers.is("ENCERRADO"))))
        .andExpect(jsonPath("$[*].fim", everyItem(notNullValue())))
        .andExpect(jsonPath("$[*].inicio", everyItem(notNullValue())));
  }

  @Test
  @DisplayName("situacao repetivel: uniao das situacoes, abertos primeiro; fim nulo nos que valem")
  void situacaoRepetivel() throws Exception {
    mockMvc.perform(como(produtorA, get(VINCULOS).param("situacao", "ATIVO")
            .param("situacao", "ENCERRADO")))
        .andExpect(header().string("X-Total-Count", "3"))
        .andExpect(jsonPath("$[*].escritorio", contains("Escritorio Ativo", "Escritorio Antigo",
            "Escritorio Mais Recente")))
        .andExpect(jsonPath("$[0].fim", nullValue()));
    mockMvc.perform(como(produtorA, get(VINCULOS).param("situacao", "ATIVO,SUSPENSO,ENCERRADO")))
        .andExpect(header().string("X-Total-Count", "4"));
    mockMvc.perform(como(produtorA, get(VINCULOS).param("situacao", "SUSPENSO")))
        .andExpect(jsonPath("$[*].escritorio", contains("Escritorio Suspenso")));
  }

  @Test
  @DisplayName("situacao vazia volta ao padrao; valor invalido e 400 no envelope errors[]")
  void vaziaEInvalida() throws Exception {
    mockMvc.perform(como(produtorA, get(VINCULOS).param("situacao", "")))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Total-Count", "2"));
    mockMvc.perform(como(produtorA, get(VINCULOS).param("situacao", "TODOS")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.errors[0].field").value("situacao"))
        .andExpect(jsonPath("$.errors[0].rejectedValue").value("TODOS"));
  }

  @Test
  @DisplayName("dois produtores: cada um so ve os proprios vinculos, em qualquer situacao")
  void cadaProdutorVeOsProprios() throws Exception {
    mockMvc.perform(como(produtorB, get(VINCULOS).param("situacao", "ATIVO")
            .param("situacao", "SUSPENSO").param("situacao", "ENCERRADO")))
        .andExpect(header().string("X-Total-Count", "2"))
        .andExpect(jsonPath("$[*].escritorio",
            containsInAnyOrder("Escritorio Ativo", "Escritorio do B")));
    mockMvc.perform(como(produtorB, get(VINCULOS).param("situacao", "ENCERRADO")))
        .andExpect(jsonPath("$[*].escritorio", contains("Escritorio do B")));
    // A nao ve o encerrado de B, e B nao ve os de A.
    mockMvc.perform(como(produtorA, get(VINCULOS).param("situacao", "ENCERRADO")))
        .andExpect(jsonPath("$[*].escritorio",
            org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("Escritorio do B"))));
  }

  @Test
  @DisplayName("contador continua com 403, com ou sem o parametro")
  void contadorNaoEntra() throws Exception {
    mockMvc.perform(como(contador, get(VINCULOS)))
        .andExpect(status().isForbidden());
    mockMvc.perform(como(contador, get(VINCULOS).param("situacao", "ENCERRADO")))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("o vinculo que o produtor encerra passa a aparecer no historico, com fim")
  void encerrarEntraNoHistorico() throws Exception {
    String id = vinculoRepository.findAll().stream()
        .filter(v -> v.getProdutorId().equals(produtorA.getId())
            && v.getSituacao() == SituacaoVinculo.ATIVO)
        .findFirst().orElseThrow().getId().toString();

    mockMvc.perform(como(produtorA, post("/api/vinculos/{id}/encerrar", id)))
        .andExpect(status().isOk());

    mockMvc.perform(como(produtorA, get(VINCULOS)))
        .andExpect(jsonPath("$[*].escritorio", contains("Escritorio Suspenso")));
    mockMvc.perform(como(produtorA, get(VINCULOS).param("situacao", "ENCERRADO")))
        .andExpect(header().string("X-Total-Count", "3"))
        // Acabou de terminar: e o primeiro do historico.
        .andExpect(jsonPath("$[0].escritorio").value("Escritorio Ativo"))
        .andExpect(jsonPath("$[0].situacao").value("ENCERRADO"))
        .andExpect(jsonPath("$[0].fim", notNullValue()));
  }

  // -------------------------------------------------------------------------

  private MockHttpServletRequestBuilder como(Produtor produtor,
      MockHttpServletRequestBuilder requisicao) {
    return requisicao.header(MockIdentidadeFilter.HEADER_USUARIO_ID, produtor.getId());
  }

  private MockHttpServletRequestBuilder como(Contador contador,
      MockHttpServletRequestBuilder requisicao) {
    return requisicao.header(MockIdentidadeFilter.HEADER_USUARIO_ID, contador.getId());
  }

  private Escritorio escritorio(String nome) {
    return escritorioRepository.save(Escritorio.builder().nome(nome).build());
  }

  private void vincular(Escritorio escritorio, Produtor produtor, SituacaoVinculo situacao,
      OffsetDateTime inicio, OffsetDateTime fim) {
    vinculoRepository.save(VinculoCarteira.builder().escritorioId(escritorio.getId())
        .produtorId(produtor.getId()).situacao(situacao).inicio(inicio).fim(fim).build());
  }
}
