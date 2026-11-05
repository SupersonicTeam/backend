package br.com.agrocontabil.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.agrocontabil.config.LimiteCorpoAssistenteFilter;
import br.com.agrocontabil.config.MockIdentidadeFilter;
import br.com.agrocontabil.model.AssistenteInteracao;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.TipoTitularCota;
import br.com.agrocontabil.model.UsoSemanalAssistente;
import br.com.agrocontabil.model.UsoSemanalAssistenteId;
import br.com.agrocontabil.repository.AssistenteInteracaoRepository;
import br.com.agrocontabil.repository.ContadorRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.repository.UsoSemanalAssistenteRepository;
import br.com.agrocontabil.support.ChatModelSimulado;
import br.com.agrocontabil.support.TestDatabaseCleaner;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Cota semanal da Ceres pela API (issue #166), com a cota ligada e um plano pequeno (100
 * créditos; o teto de uma pergunta é 73).
 */
@SpringBootTest
@Import(TestDatabaseCleaner.class)
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "app.assistente.cota.habilitada=true",
    "app.assistente.cota.creditos-por-semana-produtor=100"})
@Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
class AssistenteCotaIntegrationTest {

  private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

  @Autowired private WebApplicationContext webApplicationContext;
  @Autowired private ProdutorRepository produtorRepository;
  @Autowired private ContadorRepository contadorRepository;
  @Autowired private UsoSemanalAssistenteRepository usoRepository;
  @Autowired private AssistenteInteracaoRepository interacaoRepository;
  @Autowired private LimiteCorpoAssistenteFilter limiteCorpoFilter;
  @Autowired private ChatModelSimulado modelo;
  @Autowired private TestDatabaseCleaner cleaner;

  private MockMvc mockMvc;
  private Produtor produtor;

  @BeforeEach
  void setUp() {
    cleaner.limparTudo();
    usoRepository.deleteAll();
    modelo.reiniciar();
    modelo.classificarComo("FORA_DE_ESCOPO");
    produtor = produtorRepository.save(Produtor.builder()
        .nomeCompleto("Fazenda Cota").cpfCnpj("98765432100").estado("MT").build());
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
  @DisplayName("DoD #166: consumo da semana bate com os tokens gravados na auditoria")
  void consumoBateComAuditoria() throws Exception {
    perguntar("/api/assistente/chat", "Quem ganhou o jogo?").andExpect(status().isOk());

    AssistenteInteracao interacao =
        interacaoRepository.findByProdutorIdOrderByCriadoEmDesc(produtor.getId()).get(0);
    UsoSemanalAssistente uso = uso();
    // A classificacao chamou o modelo (simulado, sem uso informado): tokens estimados e cobrados.
    assertThat(interacao.isTokensEstimados()).isTrue();
    assertThat(uso.getTokensEntrada()).isEqualTo(interacao.getTokensEntrada().longValue());
    assertThat(uso.getTokensSaida()).isEqualTo(interacao.getTokensSaida().longValue());
    assertThat(uso.getCreditosConsumidos()).isPositive();
    assertThat(uso.getCreditosReservados()).isZero();
    assertThat(uso.getPerguntas()).isEqualTo(1);

    mockMvc.perform(get("/api/assistente/uso")
            .header(MockIdentidadeFilter.HEADER_USUARIO_ID, produtor.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.habilitada").value(true))
        .andExpect(jsonPath("$.creditosSemana").value(100))
        .andExpect(jsonPath("$.consumidos").value(uso.getCreditosConsumidos()))
        .andExpect(jsonPath("$.restantes").value(100 - uso.getCreditosConsumidos()))
        // O usuario ve creditos, nunca tokens.
        .andExpect(jsonPath("$.tokensEntrada").doesNotExist());
  }

  @Test
  @DisplayName("Sem saldo: 429 LIMITE_USO_ASSISTENTE com Retry-After, sem chamar o modelo")
  void semSaldo429() throws Exception {
    gastar(50); // 50 + 73 de teto > 100

    perguntar("/api/assistente/chat", "Quanto devo de IBS?")
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists("Retry-After"))
        .andExpect(jsonPath("$.errors[0].code").value("LIMITE_USO_ASSISTENTE"))
        .andExpect(jsonPath("$.errors[0].message").value(
            org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("token"))));
    assertThat(modelo.classificacoesRecebidas()).isEmpty();
    assertThat(modelo.promptsRecebidos()).isEmpty();
    assertThat(uso().getCreditosReservados()).isZero();
  }

  @Test
  @DisplayName("#343: atalho da tela sem saldo responde: sem modelo, nao reserva nem gasta credito")
  void atalhoSemSaldoResponde() throws Exception {
    gastar(50); // 50 + 73 de teto > 100: pergunta digitada levaria 429

    perguntar("/api/assistente/chat", "O que muda para mim com o IBS e o CBS?")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rota").value("LEGISLACAO"));
    assertThat(modelo.classificacoesRecebidas()).isEmpty();
    assertThat(modelo.promptsRecebidos()).isEmpty();
    UsoSemanalAssistente uso = uso();
    assertThat(uso.getCreditosConsumidos()).isEqualTo(50);
    assertThat(uso.getCreditosReservados()).isZero();
  }

  @Test
  @DisplayName("#343: atalho da tela sem saldo no streaming: o stream abre e responde, sem 429")
  void atalhoSemSaldoNoStream() throws Exception {
    gastar(50);

    org.springframework.test.web.servlet.MvcResult inicio = mockMvc.perform(
            post("/api/assistente/chat/stream")
                .header(MockIdentidadeFilter.HEADER_USUARIO_ID, produtor.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .content("{\"pergunta\":\"O que muda para mim com o IBS e o CBS?\"}"))
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request()
            .asyncStarted())
        .andReturn();
    // Espera o fim do stream (o emissor completa), com prazo: nada de sleep fixo.
    inicio.getAsyncResult(10_000);

    assertThat(inicio.getResponse().getContentAsString())
        .contains("event:rota").contains("LEGISLACAO").contains("event:resposta")
        .doesNotContain("LIMITE_USO_ASSISTENTE");
    assertThat(modelo.promptsRecebidos()).isEmpty();
    UsoSemanalAssistente uso = uso();
    assertThat(uso.getCreditosConsumidos()).isEqualTo(50);
    assertThat(uso.getCreditosReservados()).isZero();
  }

  @Test
  @DisplayName("Sem saldo no streaming: 429 JSON antes de o stream abrir")
  void semSaldoAntesDoStream() throws Exception {
    gastar(50);

    mockMvc.perform(post("/api/assistente/chat/stream")
            .header(MockIdentidadeFilter.HEADER_USUARIO_ID, produtor.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .content("{\"pergunta\":\"Quanto devo de IBS?\"}"))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists("Retry-After"))
        .andExpect(header().string("Content-Type",
            org.hamcrest.Matchers.startsWith(MediaType.APPLICATION_JSON_VALUE)))
        .andExpect(jsonPath("$.errors[0].code").value("LIMITE_USO_ASSISTENTE"));
  }

  private void gastar(long creditos) {
    usoRepository.save(UsoSemanalAssistente.builder()
        .id(idDaSemana())
        .creditosConsumidos(creditos)
        .atualizadoEm(OffsetDateTime.now(ZONE))
        .build());
  }

  private UsoSemanalAssistente uso() {
    return usoRepository.findById(idDaSemana()).orElseThrow();
  }

  private UsoSemanalAssistenteId idDaSemana() {
    LocalDate segunda = LocalDate.now(ZONE).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    return new UsoSemanalAssistenteId(TipoTitularCota.PRODUTOR, produtor.getId(), segunda);
  }

  private ResultActions perguntar(String caminho, String pergunta) throws Exception {
    return mockMvc.perform(post(caminho)
        .header(MockIdentidadeFilter.HEADER_USUARIO_ID, produtor.getId())
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"pergunta\":\"" + pergunta + "\"}"));
  }
}
