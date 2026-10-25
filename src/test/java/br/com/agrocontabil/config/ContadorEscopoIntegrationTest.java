package br.com.agrocontabil.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.agrocontabil.model.Contador;
import br.com.agrocontabil.model.GuiaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.SituacaoVinculo;
import br.com.agrocontabil.model.StatusGuiaFiscal;
import br.com.agrocontabil.model.TipoGuiaFiscal;
import br.com.agrocontabil.model.VinculoCarteira;
import br.com.agrocontabil.repository.ContadorRepository;
import br.com.agrocontabil.repository.GuiaFiscalRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.repository.VinculoCarteiraRepository;
import br.com.agrocontabil.support.TestDatabaseCleaner;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Prova, ponta a ponta, o escopo do papel Contador — issue #93.
 *
 * <p>São três perguntas distintas, e cada uma tem uma resposta HTTP própria:
 *
 * <ul>
 *   <li>contador <strong>com</strong> vínculo lê e grava os dados daquele produtor — 200/201;
 *   <li>contador <strong>sem</strong> vínculo recebe <strong>404</strong>, nunca 403: dizer "proibido"
 *       confirmaria que aquele produtor existe;
 *   <li>contador em operação que é do produtor (pagar a guia) recebe <strong>403</strong>: o papel é
 *       que não faz aquilo, e isso não revela dado de terceiro.
 * </ul>
 *
 * <p>Como no teste de identidade multi-usuário, o {@link MockIdentidadeFilter} é registrado à mão no
 * {@link MockMvc} — o profile daqui é {@code test} e o filtro é {@code @Profile("dev")}, mas o que
 * se quer exercitar é exatamente o filtro de produção.
 */
@SpringBootTest
@Import(TestDatabaseCleaner.class)
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
@DisplayName("Escopo do papel Contador via X-Usuario-Id (#93)")
class ContadorEscopoIntegrationTest {

  @Autowired private WebApplicationContext webApplicationContext;
  @Autowired private ProdutorRepository produtorRepository;
  @Autowired private ContadorRepository contadorRepository;
  @Autowired private VinculoCarteiraRepository vinculoRepository;
  @Autowired private GuiaFiscalRepository guiaFiscalRepository;
  @Autowired private TestDatabaseCleaner cleaner;

  private MockMvc mockMvc;
  private Produtor atendido;
  private Produtor naoAtendido;
  private Contador contador;

  @BeforeEach
  void setUp() {
    cleaner.limparTudo();

    atendido = produtorRepository.save(
        Produtor.builder().nomeCompleto("Fazenda Atendida").cpfCnpj("98765432100").estado("MT")
            .build());
    naoAtendido = produtorRepository.save(
        Produtor.builder().nomeCompleto("Sitio Vizinho").cpfCnpj("11122233344").estado("PR")
            .build());

    Contador novo = Contador.builder()
        .nomeCompleto("Escritorio Campo Certo")
        .cpfCnpj("12.345.678/0001-95")
        .build();
    contador = contadorRepository.save(novo);
    vinculoRepository.save(VinculoCarteira.builder().contadorId(contador.getId())
        .produtorId(atendido.getId()).situacao(SituacaoVinculo.ATIVO)
        .inicio(OffsetDateTime.now()).build());

    guiaFiscalRepository.save(guia(atendido));
    guiaFiscalRepository.save(guia(naoAtendido));

    mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
        .addFilters(new MockIdentidadeFilter(produtorRepository, contadorRepository))
        .build();
  }

  private GuiaFiscal guia(Produtor produtor) {
    return GuiaFiscal.builder()
        .produtor(produtor)
        .tipoGuia(TipoGuiaFiscal.ICMS)
        .dataVencimento(LocalDate.of(2026, 8, 20))
        .valor(new BigDecimal("1250.00"))
        .status(StatusGuiaFiscal.PENDENTE)
        .build();
  }

  @Test
  @DisplayName("contador vinculado lista as guias do produtor da carteira")
  void contadorVinculadoListaGuias() throws Exception {
    mockMvc
        .perform(get("/api/contador/guias")
            .param("produtorId", atendido.getId().toString())
            .header(MockIdentidadeFilter.HEADER_USUARIO_ID, contador.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].produtorId").value(atendido.getId().toString()));
  }

  @Test
  @DisplayName("contador sem vinculo recebe 404 ao listar guias — nao 403 (nao revela existencia)")
  void contadorSemVinculoNaoListaGuias() throws Exception {
    mockMvc
        .perform(get("/api/contador/guias")
            .param("produtorId", naoAtendido.getId().toString())
            .header(MockIdentidadeFilter.HEADER_USUARIO_ID, contador.getId()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errors[0].code").value("RESOURCE_NOT_FOUND"));
  }

  @Test
  @DisplayName("contador vinculado cria guia para o produtor da carteira")
  void contadorVinculadoCriaGuia() throws Exception {
    mockMvc
        .perform(post("/api/contador/guias")
            .header(MockIdentidadeFilter.HEADER_USUARIO_ID, contador.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(payloadGuia(atendido)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.produtorId").value(atendido.getId().toString()))
        .andExpect(jsonPath("$.status").value("PENDENTE"));
  }

  @Test
  @DisplayName("contador sem vinculo recebe 404 ao criar guia e nada e gravado")
  void contadorSemVinculoNaoCriaGuia() throws Exception {
    mockMvc
        .perform(post("/api/contador/guias")
            .header(MockIdentidadeFilter.HEADER_USUARIO_ID, contador.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(payloadGuia(naoAtendido)))
        .andExpect(status().isNotFound());

    // A guia do setUp continua sendo a unica do produtor alheio.
    mockMvc
        .perform(get("/api/contador/guias")
            .param("produtorId", naoAtendido.getId().toString())
            .header(MockIdentidadeFilter.HEADER_USUARIO_ID, naoAtendido.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1));
  }

  @Test
  @DisplayName("contador vinculado le o calendario fiscal do produtor da carteira")
  void contadorVinculadoLeCalendario() throws Exception {
    mockMvc
        .perform(get("/api/calendario-fiscal/{id}/vencimentos", atendido.getId())
            .param("de", "2026-01-01")
            .param("ate", "2026-12-31")
            .header(MockIdentidadeFilter.HEADER_USUARIO_ID, contador.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1));
  }

  @Test
  @DisplayName("contador sem vinculo recebe 404 no calendario do produtor alheio")
  void contadorSemVinculoNaoLeCalendario() throws Exception {
    mockMvc
        .perform(get("/api/calendario-fiscal/{id}/vencimentos", naoAtendido.getId())
            .param("de", "2026-01-01")
            .param("ate", "2026-12-31")
            .header(MockIdentidadeFilter.HEADER_USUARIO_ID, contador.getId()))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("pagar a guia e acao do produtor: contador recebe 403, nao 404")
  void contadorNaoPagaGuia() throws Exception {
    GuiaFiscal guiaDoAtendido = guiaFiscalRepository
        .findByProdutorId(atendido.getId(), org.springframework.data.domain.Pageable.ofSize(1))
        .getContent().get(0);

    mockMvc
        .perform(patch("/api/contador/guias/{id}/pagar", guiaDoAtendido.getId())
            .header(MockIdentidadeFilter.HEADER_USUARIO_ID, contador.getId()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errors[0].code").value("PAPEL_NAO_AUTORIZADO"));
  }

  @Test
  @DisplayName("o produtor continua acessando os proprios dados como antes (#94 preservado)")
  void produtorContinuaAcessandoOsProprios() throws Exception {
    mockMvc
        .perform(get("/api/contador/guias")
            .param("produtorId", atendido.getId().toString())
            .header(MockIdentidadeFilter.HEADER_USUARIO_ID, atendido.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1));

    mockMvc
        .perform(get("/api/contador/guias")
            .param("produtorId", naoAtendido.getId().toString())
            .header(MockIdentidadeFilter.HEADER_USUARIO_ID, atendido.getId()))
        .andExpect(status().isNotFound());
  }

  private String payloadGuia(Produtor produtor) {
    return """
        {
          "produtorId": "%s",
          "tipoGuia": "DARF",
          "dataVencimento": "2026-09-20",
          "valor": 500.00
        }
        """.formatted(produtor.getId());
  }
}
