package br.com.agrocontabil.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.agrocontabil.config.MockIdentidadeFilter;
import br.com.agrocontabil.model.Contador;
import br.com.agrocontabil.model.Escritorio;
import br.com.agrocontabil.model.FuncaoEscritorio;
import br.com.agrocontabil.model.GuiaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.SituacaoVinculo;
import br.com.agrocontabil.model.StatusGuiaFiscal;
import br.com.agrocontabil.model.TipoGuiaFiscal;
import br.com.agrocontabil.model.VinculoCarteira;
import br.com.agrocontabil.repository.ContadorRepository;
import br.com.agrocontabil.repository.EscritorioRepository;
import br.com.agrocontabil.repository.GuiaFiscalRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.repository.VinculoCarteiraRepository;
import br.com.agrocontabil.support.TestDatabaseCleaner;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Guias do produtor em {@code /api/guias} e da carteira em
 * {@code /api/contador/produtores/{produtorId}/guias} (issue #273), com as rotas antigas ainda
 * respondendo. Identidade pelo {@link MockIdentidadeFilter}, como nos outros testes de papel.
 */
@SpringBootTest
@Import(TestDatabaseCleaner.class)
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
class GuiaRotasSeparadasIntegrationTest {

  private static final String CORPO = """
      {"tipoGuia":"ICMS","dataVencimento":"2026-08-20","valor":1250.00,"observacoes":"ago/2026"}
      """;

  @Autowired private WebApplicationContext webApplicationContext;
  @Autowired private ProdutorRepository produtorRepository;
  @Autowired private ContadorRepository contadorRepository;
  @Autowired private VinculoCarteiraRepository vinculoRepository;
  @Autowired private EscritorioRepository escritorioRepository;
  @Autowired private GuiaFiscalRepository guiaRepository;
  @Autowired private TestDatabaseCleaner cleaner;

  private MockMvc mockMvc;
  private Produtor atendido;
  private Produtor outro;
  private Contador contador;

  @BeforeEach
  void setUp() {
    cleaner.limparTudo();
    atendido = produtorRepository.save(Produtor.builder().nomeCompleto("Fazenda Atendida")
        .cpfCnpj("98765432100").estado("MT").build());
    outro = produtorRepository.save(Produtor.builder().nomeCompleto("Sitio Vizinho")
        .cpfCnpj("11122233344").estado("PR").build());
    Escritorio escritorio = escritorioRepository.save(
        Escritorio.builder().nome("Escritorio Campo Certo").build());
    contador = contadorRepository.save(Contador.builder()
        .escritorioId(escritorio.getId()).funcao(FuncaoEscritorio.ADMINISTRADOR)
        .nomeCompleto("Escritorio Campo Certo").cpfCnpj("12.345.678/0001-95").build());
    vinculoRepository.save(VinculoCarteira.builder().escritorioId(escritorio.getId())
        .produtorId(atendido.getId()).situacao(SituacaoVinculo.ATIVO)
        .inicio(OffsetDateTime.now()).build());
    mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
        .addFilters(new MockIdentidadeFilter(produtorRepository, contadorRepository))
        .build();
  }

  @AfterEach
  void tearDown() {
    cleaner.limparTudo();
  }

  private ResultActions como(UUID usuario, MockHttpServletRequestBuilder req) throws Exception {
    return mockMvc.perform(req.header(MockIdentidadeFilter.HEADER_USUARIO_ID, usuario));
  }

  private MockHttpServletRequestBuilder criarCom(String url, String corpo) {
    return post(url).contentType(MediaType.APPLICATION_JSON).content(corpo);
  }

  private GuiaFiscal guia(Produtor produtor, int dia) {
    return guiaRepository.save(GuiaFiscal.builder().produtor(produtor)
        .tipoGuia(TipoGuiaFiscal.ICMS).dataVencimento(LocalDate.of(2026, 8, dia))
        .valor(new BigDecimal("100.00")).status(StatusGuiaFiscal.PENDENTE).build());
  }

  // ---- produtor: /api/guias -------------------------------------------------------------------

  @Test
  @DisplayName("Produtor cria a própria guia; um produtorId no corpo é ignorado")
  void produtorCriaParaSiIgnorandoProdutorIdDoCorpo() throws Exception {
    String corpoComOutro = "{\"produtorId\":\"" + outro.getId() + "\","
        + CORPO.strip().substring(1);

    como(atendido.getId(), criarCom("/api/guias", corpoComOutro))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", org.hamcrest.Matchers.endsWith("/api/guias")))
        .andExpect(jsonPath("$.produtorId").value(atendido.getId().toString()))
        .andExpect(jsonPath("$.status").value("PENDENTE"));

    assertThat(guiaRepository.findAll()).hasSize(1)
        .allSatisfy(g -> assertThat(g.getProdutor().getId()).isEqualTo(atendido.getId()));
  }

  @Test
  @DisplayName("Corpo inválido: 400 VALIDATION_ERROR")
  void corpoInvalido() throws Exception {
    como(atendido.getId(), criarCom("/api/guias", "{\"tipoGuia\":\"ICMS\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].code").value("VALIDATION_ERROR"));
  }

  @Test
  @DisplayName("Produtor lista só as próprias guias, com X-Total-Count e paginação")
  void produtorListaSoAsProprias() throws Exception {
    guia(atendido, 10);
    guia(atendido, 11);
    guia(atendido, 12);
    guia(outro, 13);

    como(atendido.getId(), get("/api/guias").param("size", "2"))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Total-Count", "3"))
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].dataVencimento").value("2026-08-12"));
    como(atendido.getId(), get("/api/guias").param("page", "1").param("size", "2"))
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].dataVencimento").value("2026-08-10"));
    como(outro.getId(), get("/api/guias"))
        .andExpect(header().string("X-Total-Count", "1"));
  }

  @Test
  @DisplayName("page e size fora da faixa: 400")
  void paginacaoForaDaFaixa() throws Exception {
    como(atendido.getId(), get("/api/guias").param("size", "201"))
        .andExpect(status().isBadRequest());
    como(atendido.getId(), get("/api/guias").param("size", "0"))
        .andExpect(status().isBadRequest());
    como(atendido.getId(), get("/api/guias").param("page", "10001"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("Produtor paga a própria guia; a de outro é 404; pagar de novo é 409")
  void produtorPaga() throws Exception {
    GuiaFiscal minha = guia(atendido, 10);
    GuiaFiscal dele = guia(outro, 11);

    como(atendido.getId(), patch("/api/guias/{id}/pagar", dele.getId()))
        .andExpect(status().isNotFound());
    como(atendido.getId(), patch("/api/guias/{id}/pagar", minha.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PAGA"));
    como(atendido.getId(), patch("/api/guias/{id}/pagar", minha.getId()))
        .andExpect(status().isConflict());
  }

  @Test
  @DisplayName("Contador nas rotas do produtor: 403 PAPEL_NAO_AUTORIZADO")
  void contadorNasRotasDoProdutor() throws Exception {
    GuiaFiscal g = guia(atendido, 10);

    como(contador.getId(), get("/api/guias"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errors[0].code").value("PAPEL_NAO_AUTORIZADO"));
    como(contador.getId(), criarCom("/api/guias", CORPO)).andExpect(status().isForbidden());
    como(contador.getId(), patch("/api/guias/{id}/pagar", g.getId()))
        .andExpect(status().isForbidden());
  }

  // ---- contador: /api/contador/produtores/{produtorId}/guias ----------------------------------

  @Test
  @DisplayName("Contador cria e lista guias de cliente da carteira; o corpo não escolhe o alvo")
  void contadorCriaEListaDaCarteira() throws Exception {
    String url = "/api/contador/produtores/" + atendido.getId() + "/guias";
    String corpoComOutro = "{\"produtorId\":\"" + outro.getId() + "\","
        + CORPO.strip().substring(1);

    como(contador.getId(), criarCom(url, corpoComOutro))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", org.hamcrest.Matchers.endsWith(url)))
        .andExpect(jsonPath("$.produtorId").value(atendido.getId().toString()));
    guia(atendido, 10);
    guia(outro, 11);

    como(contador.getId(), get(url).param("size", "1"))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Total-Count", "2"))
        .andExpect(jsonPath("$.length()").value(1));
    assertThat(guiaRepository.findAll()).hasSize(3);
  }

  @Test
  @DisplayName("Cliente fora da carteira: 404 no GET e no POST, sem gravar nada")
  void contadorForaDaCarteira() throws Exception {
    String url = "/api/contador/produtores/" + outro.getId() + "/guias";

    como(contador.getId(), get(url)).andExpect(status().isNotFound());
    como(contador.getId(), criarCom(url, CORPO)).andExpect(status().isNotFound());
    como(contador.getId(), get("/api/contador/produtores/" + UUID.randomUUID() + "/guias"))
        .andExpect(status().isNotFound());
    assertThat(guiaRepository.count()).isZero();
  }

  @Test
  @DisplayName("Vínculo suspenso deixa o cliente fora do alcance: 404")
  void vinculoSuspensoFicaFora() throws Exception {
    VinculoCarteira vinculo = vinculoRepository.findAll().get(0);
    vinculo.setSituacao(SituacaoVinculo.SUSPENSO);
    vinculoRepository.save(vinculo);

    como(contador.getId(), get("/api/contador/produtores/" + atendido.getId() + "/guias"))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("Produtor nas rotas da carteira, até para si mesmo: 403")
  void produtorNasRotasDaCarteira() throws Exception {
    String url = "/api/contador/produtores/" + atendido.getId() + "/guias";

    como(atendido.getId(), get(url))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errors[0].code").value("PAPEL_NAO_AUTORIZADO"));
    como(atendido.getId(), criarCom(url, CORPO)).andExpect(status().isForbidden());
    assertThat(guiaRepository.count()).isZero();
  }

  // ---- rotas antigas seguem iguais ------------------------------------------------------------

  @Test
  @DisplayName("Rotas antigas /api/contador/guias seguem respondendo igual")
  void rotasAntigasSeguemIguais() throws Exception {
    GuiaFiscal g = guia(atendido, 10);

    como(atendido.getId(), get("/api/contador/guias").param("produtorId",
        atendido.getId().toString()))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Total-Count", "1"));
    como(contador.getId(), get("/api/contador/guias").param("produtorId",
        atendido.getId().toString()))
        .andExpect(status().isOk());
    como(atendido.getId(), criarCom("/api/contador/guias",
        "{\"produtorId\":\"" + atendido.getId() + "\"," + CORPO.strip().substring(1)))
        .andExpect(status().isCreated());
    como(atendido.getId(), patch("/api/contador/guias/{id}/pagar", g.getId()))
        .andExpect(status().isOk());
  }
}
