package br.com.agrocontabil.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.agrocontabil.config.MockIdentidadeFilter;
import br.com.agrocontabil.model.Contador;
import br.com.agrocontabil.model.EncerradoPor;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Quem encerrou o vínculo e o motivo (issue #402), nos dois caminhos: o produtor
 * ({@code POST /api/vinculos/{id}/encerrar}) e o escritório
 * ({@code POST /api/contador/produtores/{produtorId}/encerrar}), com e sem motivo, e a leitura em
 * {@code GET /api/vinculos?situacao=ENCERRADO}. O motivo é texto livre: não pode ir para a
 * auditoria nem voltar num erro de validação.
 */
@SpringBootTest
@Import(TestDatabaseCleaner.class)
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
@DisplayName("Encerramento do vinculo: quem encerrou e o motivo (#402)")
class EncerramentoVinculoIntegrationTest {

  private static final String VINCULOS = "/api/vinculos";
  private static final String MOTIVO = "Mudanca para o escritorio do cunhado Zeca";

  @Autowired private WebApplicationContext webApplicationContext;
  @Autowired private ProdutorRepository produtorRepository;
  @Autowired private ContadorRepository contadorRepository;
  @Autowired private EscritorioRepository escritorioRepository;
  @Autowired private VinculoCarteiraRepository vinculoRepository;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private org.springframework.transaction.support.TransactionTemplate transacao;
  @Autowired private TestDatabaseCleaner cleaner;

  private MockMvc mockMvc;
  private Produtor produtor;
  private Escritorio escritorio;
  private Contador admin;
  private Contador assistente;

  @BeforeEach
  void setUp() {
    cleaner.limparTudo();
    produtor = produtorRepository.save(
        Produtor.builder().nomeCompleto("Produtor A").cpfCnpj("52998224725").build());
    escritorio = escritorioRepository.save(Escritorio.builder().nome("Escritorio Campo").build());
    admin = contadorRepository.save(Contador.builder().escritorioId(escritorio.getId())
        .funcao(FuncaoEscritorio.ADMINISTRADOR).nomeCompleto("Admin").cpfCnpj("12345678000195")
        .build());
    assistente = contadorRepository.save(Contador.builder().escritorioId(escritorio.getId())
        .funcao(FuncaoEscritorio.ASSISTENTE).nomeCompleto("Assistente")
        .cpfCnpj("22222222000191").build());
    mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
        .addFilters(new MockIdentidadeFilter(produtorRepository, contadorRepository))
        .build();
  }

  @AfterEach
  void tearDown() {
    cleaner.limparTudo();
  }

  @Test
  @DisplayName("produtor encerra com motivo: grava PRODUTOR e o motivo, e o historico devolve os dois")
  void produtorComMotivo() throws Exception {
    UUID id = vincular(SituacaoVinculo.ATIVO).getId();

    mockMvc.perform(comoProdutor(post("/api/vinculos/{id}/encerrar", id))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"motivo\":\"  " + MOTIVO + "  \"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.situacao").value("ENCERRADO"))
        .andExpect(jsonPath("$.encerradoPor").value("PRODUTOR"))
        .andExpect(jsonPath("$.motivoEncerramento").value(MOTIVO));

    VinculoCarteira gravado = vinculoRepository.findById(id).orElseThrow();
    assertThat(gravado.getEncerradoPor()).isEqualTo(EncerradoPor.PRODUTOR);
    assertThat(gravado.getEncerradoPorMembroId()).isNull();
    assertThat(gravado.getMotivoEncerramento()).isEqualTo(MOTIVO);

    mockMvc.perform(comoProdutor(get(VINCULOS).param("situacao", "ENCERRADO")))
        .andExpect(jsonPath("$[0].encerradoPor").value("PRODUTOR"))
        .andExpect(jsonPath("$[0].motivoEncerramento").value(MOTIVO));
  }

  @Test
  @DisplayName("produtor sem corpo (como hoje) ou com motivo em branco encerra sem motivo")
  void produtorSemMotivo() throws Exception {
    UUID semCorpo = vincular(SituacaoVinculo.ATIVO).getId();
    mockMvc.perform(comoProdutor(post("/api/vinculos/{id}/encerrar", semCorpo)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.encerradoPor").value("PRODUTOR"))
        .andExpect(jsonPath("$.motivoEncerramento", nullValue()));

    Escritorio outro = escritorioRepository.save(Escritorio.builder().nome("Outro").build());
    UUID emBranco = vinculoRepository.save(VinculoCarteira.builder()
        .escritorioId(outro.getId()).produtorId(produtor.getId())
        .situacao(SituacaoVinculo.ATIVO).inicio(OffsetDateTime.now()).build()).getId();
    mockMvc.perform(comoProdutor(post("/api/vinculos/{id}/encerrar", emBranco))
            .contentType(MediaType.APPLICATION_JSON).content("{\"motivo\":\"   \"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.motivoEncerramento", nullValue()));
    assertThat(vinculoRepository.findById(emBranco).orElseThrow().getMotivoEncerramento())
        .isNull();
  }

  @Test
  @DisplayName("motivo acima de 500 caracteres: 400, nada encerrado e o texto nao volta no erro")
  void motivoLongoDemais() throws Exception {
    UUID id = vincular(SituacaoVinculo.ATIVO).getId();
    String longo = "segredo-do-produtor ".repeat(30);

    mockMvc.perform(comoProdutor(post("/api/vinculos/{id}/encerrar", id))
            .contentType(MediaType.APPLICATION_JSON).content("{\"motivo\":\"" + longo + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("motivo"))
        .andExpect(content().string(not(containsString("segredo-do-produtor"))));

    assertThat(vinculoRepository.findById(id).orElseThrow().getSituacao())
        .isEqualTo(SituacaoVinculo.ATIVO);
  }

  @Test
  @DisplayName("escritorio (administrador) encerra com motivo: grava ESCRITORIO, o membro e o motivo; o produtor le")
  void escritorioComMotivo() throws Exception {
    UUID id = vincular(SituacaoVinculo.ATIVO).getId();

    mockMvc.perform(comoContador(admin, post("/api/contador/produtores/{id}/encerrar",
            produtor.getId()))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"motivo\":\"" + MOTIVO + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.situacao").value("ENCERRADO"));

    VinculoCarteira gravado = vinculoRepository.findById(id).orElseThrow();
    assertThat(gravado.getEncerradoPor()).isEqualTo(EncerradoPor.ESCRITORIO);
    assertThat(gravado.getEncerradoPorMembroId()).isEqualTo(admin.getId());
    assertThat(gravado.getMotivoEncerramento()).isEqualTo(MOTIVO);

    mockMvc.perform(comoProdutor(get(VINCULOS).param("situacao", "ENCERRADO")))
        .andExpect(jsonPath("$[0].encerradoPor").value("ESCRITORIO"))
        .andExpect(jsonPath("$[0].motivoEncerramento").value(MOTIVO))
        // So o papel e o texto: nada do membro que encerrou chega ao produtor.
        .andExpect(jsonPath("$[0].encerradoPorMembroId").doesNotExist());
  }

  @Test
  @DisplayName("escritorio sem corpo (como hoje) encerra sem motivo, ainda registrando ESCRITORIO")
  void escritorioSemMotivo() throws Exception {
    UUID id = vincular(SituacaoVinculo.ATIVO).getId();

    mockMvc.perform(comoContador(admin, post("/api/contador/produtores/{id}/encerrar",
            produtor.getId())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.situacao").value("ENCERRADO"));

    VinculoCarteira gravado = vinculoRepository.findById(id).orElseThrow();
    assertThat(gravado.getEncerradoPor()).isEqualTo(EncerradoPor.ESCRITORIO);
    assertThat(gravado.getMotivoEncerramento()).isNull();
  }

  @Test
  @DisplayName("quem nao e administrador continua 403 e o vinculo nao muda")
  void soAdministradorEncerra() throws Exception {
    UUID id = vincular(SituacaoVinculo.ATIVO).getId();

    mockMvc.perform(comoContador(assistente, post("/api/contador/produtores/{id}/encerrar",
            produtor.getId()))
            .contentType(MediaType.APPLICATION_JSON).content("{\"motivo\":\"x\"}"))
        .andExpect(status().isForbidden());

    VinculoCarteira gravado = vinculoRepository.findById(id).orElseThrow();
    assertThat(gravado.getSituacao()).isEqualTo(SituacaoVinculo.ATIVO);
    assertThat(gravado.getMotivoEncerramento()).isNull();
  }

  @Test
  @DisplayName("vinculo encerrado antes do registro: quem encerrou e motivo nulos ('nao registrado')")
  void vinculoAntigoNaoRegistrado() throws Exception {
    VinculoCarteira antigo = vincular(SituacaoVinculo.ENCERRADO);
    antigo.setFim(OffsetDateTime.now());
    vinculoRepository.save(antigo);

    mockMvc.perform(comoProdutor(get(VINCULOS).param("situacao", "ENCERRADO")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].situacao").value("ENCERRADO"))
        .andExpect(jsonPath("$[0].encerradoPor", nullValue()))
        .andExpect(jsonPath("$[0].motivoEncerramento", nullValue()));
  }

  @Test
  @DisplayName("a auditoria registra o encerramento sem o motivo, nos dois caminhos")
  void auditoriaSemMotivo() throws Exception {
    vincular(SituacaoVinculo.ATIVO);
    mockMvc.perform(comoContador(admin, post("/api/contador/produtores/{id}/encerrar",
            produtor.getId()))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"motivo\":\"" + MOTIVO + "\"}"))
        .andExpect(status().isOk());
    UUID outroVinculo = vinculoRepository.save(VinculoCarteira.builder()
        .escritorioId(escritorioRepository.save(Escritorio.builder().nome("Outro").build())
            .getId())
        .produtorId(produtor.getId()).situacao(SituacaoVinculo.ATIVO)
        .inicio(OffsetDateTime.now()).build()).getId();
    mockMvc.perform(comoProdutor(post("/api/vinculos/{id}/encerrar", outroVinculo))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"motivo\":\"" + MOTIVO + "\"}"))
        .andExpect(status().isOk());

    List<Map<String, Object>> registros = jdbc.queryForList(
        "SELECT * FROM registro_auditoria WHERE acao = 'VINCULO_ENCERRADO'");
    assertThat(registros).hasSize(2);
    assertThat(registros.toString()).doesNotContain("Zeca").doesNotContain("cunhado");
  }

  @Test
  @DisplayName("membro removido ou excluido deixa de ser apontado como autor, o registro e o motivo ficam")
  void autorDesfeitoQuandoMembroSai() throws Exception {
    UUID id = vincular(SituacaoVinculo.ATIVO).getId();
    mockMvc.perform(comoContador(admin, post("/api/contador/produtores/{id}/encerrar",
            produtor.getId()))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"motivo\":\"" + MOTIVO + "\"}"))
        .andExpect(status().isOk());

    Integer alterados = transacao.execute(
        t -> vinculoRepository.limparAutorDoEncerramento(admin.getId()));
    assertThat(alterados).isEqualTo(1);

    VinculoCarteira gravado = vinculoRepository.findById(id).orElseThrow();
    assertThat(gravado.getEncerradoPorMembroId()).isNull();
    assertThat(gravado.getEncerradoPor()).isEqualTo(EncerradoPor.ESCRITORIO);
    assertThat(gravado.getMotivoEncerramento()).isEqualTo(MOTIVO);
  }

  @Test
  @DisplayName("o toString do vinculo e do corpo nao carrega o motivo")
  void motivoForaDoToString() {
    VinculoCarteira vinculo = VinculoCarteira.builder().motivoEncerramento(MOTIVO).build();
    assertThat(vinculo.toString()).doesNotContain("Zeca");
    assertThat(new br.com.agrocontabil.dto.EncerramentoRequest(MOTIVO).toString())
        .doesNotContain("Zeca");
  }

  // -------------------------------------------------------------------------

  private VinculoCarteira vincular(SituacaoVinculo situacao) {
    return vinculoRepository.save(VinculoCarteira.builder().escritorioId(escritorio.getId())
        .produtorId(produtor.getId()).situacao(situacao)
        .inicio(OffsetDateTime.parse("2026-01-10T10:00:00-03:00")).build());
  }

  private MockHttpServletRequestBuilder comoProdutor(MockHttpServletRequestBuilder requisicao) {
    return requisicao.header(MockIdentidadeFilter.HEADER_USUARIO_ID, produtor.getId());
  }

  private MockHttpServletRequestBuilder comoContador(Contador contador,
      MockHttpServletRequestBuilder requisicao) {
    return requisicao.header(MockIdentidadeFilter.HEADER_USUARIO_ID, contador.getId());
  }
}
