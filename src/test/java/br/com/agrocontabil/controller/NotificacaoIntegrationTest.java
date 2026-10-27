package br.com.agrocontabil.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.agrocontabil.dto.CadastroRequest;
import br.com.agrocontabil.dto.CadastroResponse;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.repository.NotificacaoRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.security.PapelUsuario;
import br.com.agrocontabil.service.AvisoVencimentoJob;
import br.com.agrocontabil.service.CadastroService;
import br.com.agrocontabil.service.EnvioNotificacao;
import br.com.agrocontabil.service.NotificacaoRetencaoJob;
import br.com.agrocontabil.service.NotificacaoService;
import br.com.agrocontabil.support.TestDatabaseCleaner;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Notificação por WhatsApp (issue #182) pela cadeia real com JWT, com o relógio fixo em
 * 03/03/2026: nessa janela nenhuma obrigação do catálogo nem o fechamento da apuração (RN004)
 * cai nos próximos 7 dias, então os itens são só os que o teste cria.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestDatabaseCleaner.class, NotificacaoIntegrationTest.Config.class})
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
@DisplayName("Notificacao por WhatsApp (#182)")
class NotificacaoIntegrationTest {

  private static final ZoneId ZONA = ZoneId.of("America/Sao_Paulo");
  private static final LocalDate HOJE = LocalDate.of(2026, 3, 3);
  private static final String SENHA = "cavalo bateria grampo correto";
  private static final String CONTADOR = "/api/contador/produtores/{p}/notificacao";

  /** Notificações entregues à porta, com a prova de que não havia transação aberta. */
  static final List<String> ENTREGUES = new CopyOnWriteArrayList<>();

  /** Liga a falha do canal (provedor fora do ar). */
  static final AtomicBoolean CANAL_FORA = new AtomicBoolean();

  @TestConfiguration
  static class Config {
    @Bean
    @Primary
    Clock relogioFixo() {
      return Clock.fixed(ZonedDateTime.of(HOJE.atTime(9, 0), ZONA).toInstant(), ZONA);
    }

    @Bean
    @Primary
    EnvioNotificacao envioCapturado() {
      return (notificacao, destinatario) -> {
        ENTREGUES.add(notificacao.getId() + "|" + destinatario
            + "|tx=" + TransactionSynchronizationManager.isActualTransactionActive());
        if (CANAL_FORA.get()) {
          throw new IllegalStateException("provedor de WhatsApp fora do ar");
        }
      };
    }
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private CadastroService cadastroService;
  @Autowired private ProdutorRepository produtorRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private TestDatabaseCleaner cleaner;
  @Autowired private NotificacaoService notificacaoService;
  @Autowired private Clock clock;
  @Autowired private NotificacaoRepository notificacaoRepository;
  @Autowired private PlatformTransactionManager transactionManager;

  private CadastroResponse produtorA;
  private CadastroResponse produtorB;
  private UUID escritorio;
  private UUID outroEscritorio;
  private String tokenA;
  private String tokenB;
  private String tokenContador;
  private String tokenOutro;

  @BeforeEach
  void setUp() throws Exception {
    cleaner.limparTudo();
    ENTREGUES.clear();
    CANAL_FORA.set(false);
    produtorA = cadastroService.cadastrar(new CadastroRequest(
        PapelUsuario.PRODUTOR, "Produtor A", "52998224725", "a@fazenda.com.br", SENHA));
    produtorB = cadastroService.cadastrar(new CadastroRequest(
        PapelUsuario.PRODUTOR, "Produtor B", "39053344705", "b@fazenda.com.br", SENHA));
    CadastroResponse contador = cadastroService.cadastrar(new CadastroRequest(
        PapelUsuario.CONTADOR, "Escritorio", "11222333000181", "c@escritorio.com.br", SENHA));
    CadastroResponse outro = cadastroService.cadastrar(new CadastroRequest(
        PapelUsuario.CONTADOR, "Outro", "11444777000161", "o@escritorio.com.br", SENHA));
    escritorio = escritorioDe(contador.usuarioId());
    outroEscritorio = escritorioDe(outro.usuarioId());
    // Os dois escritorios atendem A; B nao e cliente de ninguem.
    vincular(produtorA.usuarioId(), escritorio);
    vincular(produtorA.usuarioId(), outroEscritorio);
    whatsapp(produtorA.usuarioId(), "65999990000");
    tokenA = token("a@fazenda.com.br");
    tokenB = token("b@fazenda.com.br");
    tokenContador = token("c@escritorio.com.br");
    tokenOutro = token("o@escritorio.com.br");
  }

  private UUID escritorioDe(UUID contadorId) {
    return jdbcTemplate.queryForObject("SELECT escritorio_id FROM contadores WHERE id = ?",
        UUID.class, contadorId);
  }

  private void vincular(UUID produtorId, UUID escritorioId) {
    jdbcTemplate.update("INSERT INTO carteira_vinculo (id, escritorio_id, produtor_id, situacao,"
        + " inicio) VALUES (RANDOM_UUID(), ?, ?, 'ATIVO', CURRENT_TIMESTAMP)", escritorioId,
        produtorId);
  }

  private void whatsapp(UUID produtorId, String telefone) {
    Produtor produtor = produtorRepository.findById(produtorId).orElseThrow();
    produtor.setTelefone(telefone);
    produtorRepository.save(produtor);
  }

  private void guia(UUID produtorId, LocalDate vencimento, String status, String obs) {
    jdbcTemplate.update("INSERT INTO guias_fiscais (id, produtor_id, tipo_guia, data_vencimento,"
        + " valor, observacoes, status) VALUES (RANDOM_UUID(), ?, 'ICMS', ?, 1250.00, ?, ?)",
        produtorId, vencimento, obs, status);
  }

  private void pedido(UUID produtorId, UUID escritorioId, String titulo, LocalDate prazo) {
    jdbcTemplate.update("INSERT INTO pendencia (id, escritorio_id, produtor_id, titulo,"
        + " data_limite, status) VALUES (RANDOM_UUID(), ?, ?, ?, ?, 'PENDENTE')", escritorioId,
        produtorId, titulo, prazo);
  }

  private String token(String email) throws Exception {
    MvcResult login = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
        .content("{\"email\":\"%s\",\"senha\":\"%s\"}".formatted(email, SENHA))).andReturn();
    return "Bearer " + objectMapper.readTree(login.getResponse().getContentAsString())
        .get("accessToken").asText();
  }

  private int notificacoes() {
    return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notificacao", Integer.class);
  }

  @Test
  @DisplayName("proximas: urgentes primeiro, paga e fora dos 7 dias ficam de fora; auditado e"
      + " entregue fora de transacao")
  void proximasDoProdutor() throws Exception {
    guia(produtorA.usuarioId(), HOJE.plusDays(1), "PENDENTE", "amanha");
    guia(produtorA.usuarioId(), HOJE.plusDays(5), "PENDENTE", "em cinco dias");
    guia(produtorA.usuarioId(), HOJE.plusDays(1), "PAGA", "paga");
    guia(produtorA.usuarioId(), HOJE.plusDays(10), "PENDENTE", "longe");

    mockMvc.perform(post("/api/notificacao/proximas-obrigacoes")
            .header(HttpHeaders.AUTHORIZATION, tokenA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enviados").value(2))
        .andExpect(jsonPath("$.urgentes").value(1))
        .andExpect(jsonPath("$.produtorId").doesNotExist());

    MvcResult lista = mockMvc.perform(get("/api/notificacao")
            .header(HttpHeaders.AUTHORIZATION, tokenA))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Total-Count", "1"))
        .andExpect(jsonPath("$[0].tipo").value("PROXIMAS_OBRIGACOES"))
        .andExpect(jsonPath("$[0].canal").value("WHATSAPP"))
        .andExpect(jsonPath("$[0].status").value("REGISTRADA"))
        .andExpect(jsonPath("$[0].disparadoPor").value("PRODUTOR"))
        .andReturn();
    String[] linhas = objectMapper.readTree(lista.getResponse().getContentAsString())
        .at("/0/texto").asText().split("\n");
    assertThat(linhas).hasSize(3);
    assertThat(linhas[1]).startsWith("[URGENTE] 04/03/2026");
    assertThat(linhas[2]).startsWith("08/03/2026");

    assertThat(ENTREGUES).singleElement().asString().endsWith("|65999990000|tx=false");
    assertThat(jdbcTemplate.queryForList("SELECT acao FROM registro_auditoria"
        + " WHERE produtor_id = ?", String.class, produtorA.usuarioId()))
        .contains("NOTIFICACAO_REGISTRADA");
  }

  @Test
  @DisplayName("canal fora do ar depois do commit: o registro vale, 200, e o job segue")
  void canalForaDoAr() throws Exception {
    CANAL_FORA.set(true);
    guia(produtorA.usuarioId(), HOJE.plusDays(1), "PENDENTE", "amanha");
    pedido(produtorA.usuarioId(), escritorio, "Extrato", HOJE.plusDays(1));

    mockMvc.perform(post("/api/notificacao/enviar").header(HttpHeaders.AUTHORIZATION, tokenA)
            .contentType(MediaType.APPLICATION_JSON).content("{\"mensagem\":\"Oi\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enviados").value(1));
    // Os dois avisos de amanha sao tentados, mesmo com o primeiro falhando no canal.
    assertThat(notificacaoService.avisarVencimentosDeAmanha()).isEqualTo(2);

    assertThat(notificacoes()).isEqualTo(3);
    assertThat(ENTREGUES).hasSize(3);
  }

  @Test
  @DisplayName("nada a avisar: 200 com enviados 0 e nada registrado")
  void nadaAAvisar() throws Exception {
    mockMvc.perform(post("/api/notificacao/proximas-obrigacoes")
            .header(HttpHeaders.AUTHORIZATION, tokenA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enviados").value(0))
        .andExpect(jsonPath("$.notificacaoId").doesNotExist());

    assertThat(notificacoes()).isZero();
    assertThat(ENTREGUES).isEmpty();
  }

  @Test
  @DisplayName("sem WhatsApp: 422 CONTATO_AUSENTE, mesmo sem nada a avisar, e nada registrado")
  void contatoAusente() throws Exception {
    mockMvc.perform(post("/api/notificacao/enviar").header(HttpHeaders.AUTHORIZATION, tokenB)
            .contentType(MediaType.APPLICATION_JSON).content("{\"mensagem\":\"Oi\"}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.errors[0].code").value("CONTATO_AUSENTE"));
    mockMvc.perform(post("/api/notificacao/proximas-obrigacoes")
            .header(HttpHeaders.AUTHORIZATION, tokenB))
        .andExpect(status().isUnprocessableEntity());

    assertThat(notificacoes()).isZero();
  }

  @Test
  @DisplayName("atrasados: guia nao paga com data passada e pedido com prazo passado")
  void atrasados() throws Exception {
    guia(produtorA.usuarioId(), HOJE.minusDays(33), "VENCIDA", "vencida");
    guia(produtorA.usuarioId(), HOJE.minusDays(1), "PENDENTE", "ainda nao marcada");
    guia(produtorA.usuarioId(), HOJE.minusDays(5), "PAGA", "paga");
    pedido(produtorA.usuarioId(), escritorio, "Extrato de fevereiro", HOJE.minusDays(2));

    mockMvc.perform(post("/api/notificacao/todos-atrasados")
            .header(HttpHeaders.AUTHORIZATION, tokenA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enviados").value(3))
        .andExpect(jsonPath("$.urgentes").value(0));
    assertThat(jdbcTemplate.queryForObject("SELECT texto FROM notificacao", String.class))
        .contains("[ATRASADO]", "Extrato de fevereiro");
  }

  @Test
  @DisplayName("mensagem livre: 1 a 500 caracteres (400 fora)")
  void mensagem() throws Exception {
    mockMvc.perform(post("/api/notificacao/enviar").header(HttpHeaders.AUTHORIZATION, tokenA)
            .contentType(MediaType.APPLICATION_JSON).content("{\"mensagem\":\"  Lembrete  \"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enviados").value(1));
    mockMvc.perform(post("/api/notificacao/enviar").header(HttpHeaders.AUTHORIZATION, tokenA)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"mensagem\":\"" + "x".repeat(501) + "\"}"))
        .andExpect(status().isBadRequest());
    mockMvc.perform(post("/api/notificacao/enviar").header(HttpHeaders.AUTHORIZATION, tokenA)
            .contentType(MediaType.APPLICATION_JSON).content("{\"mensagem\":\"   \"}"))
        .andExpect(status().isBadRequest());

    assertThat(jdbcTemplate.queryForObject("SELECT texto FROM notificacao", String.class))
        .isEqualTo("Lembrete");
  }

  @Test
  @DisplayName("teto de 10 disparos por hora: 429 com Retry-After; o aviso automatico nao conta")
  void limitePorHora() throws Exception {
    for (int i = 0; i < 10; i++) {
      mockMvc.perform(post("/api/notificacao/enviar").header(HttpHeaders.AUTHORIZATION, tokenA)
              .contentType(MediaType.APPLICATION_JSON).content("{\"mensagem\":\"m" + i + "\"}"))
          .andExpect(status().isOk());
    }
    mockMvc.perform(post(CONTADOR + "/enviar", produtorA.usuarioId())
            .header(HttpHeaders.AUTHORIZATION, tokenContador)
            .contentType(MediaType.APPLICATION_JSON).content("{\"mensagem\":\"de mais\"}"))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists("Retry-After"))
        .andExpect(jsonPath("$.errors[0].code").value("LIMITE_NOTIFICACOES"));
    assertThat(notificacoes()).isEqualTo(10);
  }

  @Test
  @DisplayName("escritorio: so os proprios itens e o proprio historico; fora da carteira 404;"
      + " produtor 403")
  void escritorio() throws Exception {
    pedido(produtorA.usuarioId(), escritorio, "Nota de venda", HOJE.plusDays(3));
    pedido(produtorA.usuarioId(), outroEscritorio, "Contrato do outro", HOJE.plusDays(3));

    mockMvc.perform(post(CONTADOR + "/proximas-obrigacoes", produtorA.usuarioId())
            .header(HttpHeaders.AUTHORIZATION, tokenContador))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enviados").value(1));
    assertThat(jdbcTemplate.queryForObject("SELECT texto FROM notificacao", String.class))
        .contains("Nota de venda").doesNotContain("Contrato do outro");
    mockMvc.perform(post(CONTADOR + "/enviar", produtorA.usuarioId())
            .header(HttpHeaders.AUTHORIZATION, tokenOutro)
            .contentType(MediaType.APPLICATION_JSON).content("{\"mensagem\":\"do outro\"}"))
        .andExpect(status().isOk());

    mockMvc.perform(get(CONTADOR, produtorA.usuarioId())
            .header(HttpHeaders.AUTHORIZATION, tokenContador))
        .andExpect(header().string("X-Total-Count", "1"))
        .andExpect(jsonPath("$[0].disparadoPor").value("CONTADOR"));
    // O produtor ve as dos dois escritorios.
    mockMvc.perform(get("/api/notificacao").header(HttpHeaders.AUTHORIZATION, tokenA))
        .andExpect(header().string("X-Total-Count", "2"));

    // B nao e cliente: 404, nada registrado; produtor na rota do escritorio: 403.
    mockMvc.perform(post(CONTADOR + "/enviar", produtorB.usuarioId())
            .header(HttpHeaders.AUTHORIZATION, tokenContador)
            .contentType(MediaType.APPLICATION_JSON).content("{\"mensagem\":\"x\"}"))
        .andExpect(status().isNotFound());
    mockMvc.perform(get(CONTADOR, produtorB.usuarioId())
            .header(HttpHeaders.AUTHORIZATION, tokenContador))
        .andExpect(status().isNotFound());
    mockMvc.perform(post(CONTADOR + "/proximas-obrigacoes", produtorA.usuarioId())
            .header(HttpHeaders.AUTHORIZATION, tokenA))
        .andExpect(status().isForbidden());
    // Contador na rota do produtor: 403.
    mockMvc.perform(post("/api/notificacao/proximas-obrigacoes")
            .header(HttpHeaders.AUTHORIZATION, tokenContador))
        .andExpect(status().isForbidden());
    assertThat(notificacoes()).isEqualTo(2);
  }

  @Test
  @DisplayName("historico: cada produtor so ve as proprias")
  void antiIdor() throws Exception {
    mockMvc.perform(post("/api/notificacao/enviar").header(HttpHeaders.AUTHORIZATION, tokenA)
            .contentType(MediaType.APPLICATION_JSON).content("{\"mensagem\":\"de A\"}"))
        .andExpect(status().isOk());

    mockMvc.perform(get("/api/notificacao").header(HttpHeaders.AUTHORIZATION, tokenB))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Total-Count", "0"));
  }

  @Test
  @DisplayName("aviso automatico: um por item que vence amanha, uma vez so; sem WhatsApp, nada")
  void avisoAutomatico() {
    guia(produtorA.usuarioId(), HOJE.plusDays(1), "PENDENTE", "amanha");
    pedido(produtorA.usuarioId(), escritorio, "Extrato", HOJE.plusDays(1));
    guia(produtorA.usuarioId(), HOJE.plusDays(2), "PENDENTE", "depois");
    guia(produtorB.usuarioId(), HOJE.plusDays(1), "PENDENTE", "B sem whatsapp");

    new AvisoVencimentoJob(notificacaoService).avisar();
    assertThat(notificacaoService.avisarVencimentosDeAmanha()).isZero();

    assertThat(jdbcTemplate.queryForList("SELECT tipo FROM notificacao WHERE produtor_id = ?",
        String.class, produtorA.usuarioId())).containsExactly("AVISO_URGENTE", "AVISO_URGENTE");
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notificacao WHERE produtor_id"
        + " = ?", Integer.class, produtorB.usuarioId())).isZero();
    assertThat(ENTREGUES).hasSize(2).allMatch(e -> e.endsWith("tx=false"));
  }

  @Test
  @DisplayName("LGPD e retencao: a exclusao do produtor e o job de 1 ano apagam")
  void lgpdERetencao() throws Exception {
    mockMvc.perform(post("/api/notificacao/enviar").header(HttpHeaders.AUTHORIZATION, tokenA)
            .contentType(MediaType.APPLICATION_JSON).content("{\"mensagem\":\"antiga\"}"))
        .andExpect(status().isOk());
    jdbcTemplate.update("UPDATE notificacao SET criado_em = DATEADD('DAY', -366, criado_em)");
    mockMvc.perform(post("/api/notificacao/enviar").header(HttpHeaders.AUTHORIZATION, tokenA)
            .contentType(MediaType.APPLICATION_JSON).content("{\"mensagem\":\"nova\"}"))
        .andExpect(status().isOk());

    NotificacaoRetencaoJob retencao = new NotificacaoRetencaoJob(notificacaoRepository, 365, clock);
    new TransactionTemplate(transactionManager).executeWithoutResult(s -> retencao.aplicar());
    assertThat(jdbcTemplate.queryForList("SELECT texto FROM notificacao", String.class))
        .containsExactly("nova");

    mockMvc.perform(delete("/api/usuarios/{id}/dados", produtorA.usuarioId())
            .header(HttpHeaders.AUTHORIZATION, tokenA).contentType(MediaType.APPLICATION_JSON)
            .content("{\"senha\":\"" + SENHA + "\"}"))
        .andExpect(status().isNoContent());
    assertThat(notificacoes()).isZero();
  }
}
