package br.com.agrocontabil.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.agrocontabil.dto.CadastroRequest;
import br.com.agrocontabil.dto.CadastroResponse;
import br.com.agrocontabil.security.PapelUsuario;
import br.com.agrocontabil.service.CadastroService;
import br.com.agrocontabil.support.TestDatabaseCleaner;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.YearMonth;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Reabertura da apuração pelo escritório (issue #224, RN005), pela cadeia real com JWT.
 *
 * <p>A RN005 diz que o contador reabre; até a #224 o produtor reabria o próprio mês fechado e o
 * contador recebia 403. A rota é a do padrão da carteira, pelo período (o id da apuração não sai
 * na API), e só administrador e contador do escritório reabrem — assistente, não.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestDatabaseCleaner.class)
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
@DisplayName("Reabertura da apuracao pelo contador da carteira (#224)")
class CarteiraApuracaoIntegrationTest {

  private static final String SENHA = "cavalo bateria grampo correto";
  private static final String ROTA = "/api/contador/produtores/{p}/apuracao/{periodo}/reabrir";

  @Autowired private MockMvc mockMvc;
  @Autowired private CadastroService cadastroService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private TestDatabaseCleaner cleaner;
  @Autowired private Clock clock;

  private CadastroResponse produtorA;
  private CadastroResponse produtorB;
  private CadastroResponse contador;
  private String tokenA;
  private String tokenContador;
  private String tokenOutroEscritorio;
  private String mesPassado;
  private UUID apuracaoA;

  @BeforeEach
  void setUp() throws Exception {
    cleaner.limparTudo();
    produtorA = cadastroService.cadastrar(new CadastroRequest(
        PapelUsuario.PRODUTOR, "Produtor A", "52998224725", "a@fazenda.com.br", SENHA));
    produtorB = cadastroService.cadastrar(new CadastroRequest(
        PapelUsuario.PRODUTOR, "Produtor B", "39053344705", "b@fazenda.com.br", SENHA));
    contador = cadastroService.cadastrar(new CadastroRequest(
        PapelUsuario.CONTADOR, "Escritorio", "11222333000181", "c@escritorio.com.br", SENHA));
    cadastroService.cadastrar(new CadastroRequest(
        PapelUsuario.CONTADOR, "Outro Escritorio", "11444777000161", "o@escritorio.com.br",
        SENHA));
    // So o produtor A esta na carteira do escritorio.
    jdbcTemplate.update("INSERT INTO carteira_vinculo (id, escritorio_id, produtor_id, situacao,"
        + " inicio) SELECT RANDOM_UUID(), escritorio_id, ?, 'ATIVO', CURRENT_TIMESTAMP"
        + " FROM contadores WHERE id = ?", produtorA.usuarioId(), contador.usuarioId());

    mesPassado = YearMonth.now(clock).minusMonths(1).toString();
    apuracaoA = fechada(produtorA.usuarioId(), mesPassado);
    fechada(produtorB.usuarioId(), mesPassado);

    tokenA = token("a@fazenda.com.br");
    tokenContador = token("c@escritorio.com.br");
    tokenOutroEscritorio = token("o@escritorio.com.br");
  }

  private UUID fechada(UUID produtorId, String periodo) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update("INSERT INTO apuracao_mensal (id, produtor_id, periodo, status,"
        + " debito_ibs, debito_cbs, credito_ibs, credito_cbs, saldo_ibs, saldo_cbs, total_notas,"
        + " data_fechamento) VALUES (?, ?, ?, 'FECHADO', 10, 20, 1, 2, 9, 18, 3,"
        + " CURRENT_TIMESTAMP)", id, produtorId, periodo);
    return id;
  }

  private String token(String email) throws Exception {
    MvcResult login = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
        .content("{\"email\":\"%s\",\"senha\":\"%s\"}".formatted(email, SENHA))).andReturn();
    return "Bearer " + objectMapper.readTree(login.getResponse().getContentAsString())
        .get("accessToken").asText();
  }

  private String statusNoBanco(UUID produtorId, String periodo) {
    return jdbcTemplate.queryForObject("SELECT status FROM apuracao_mensal WHERE produtor_id = ?"
        + " AND periodo = ?", String.class, produtorId, periodo);
  }

  private void funcao(String funcao) {
    jdbcTemplate.update("UPDATE contadores SET funcao_escritorio = ? WHERE id = ?", funcao,
        contador.usuarioId());
  }

  @Test
  @DisplayName("contador administrador da carteira reabre o mes passado: 200 ABERTO, auditado")
  void administradorReabre() throws Exception {
    mockMvc.perform(post(ROTA, produtorA.usuarioId(), mesPassado)
            .header(HttpHeaders.AUTHORIZATION, tokenContador))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.periodo").value(mesPassado))
        .andExpect(jsonPath("$.status").value("ABERTO"))
        .andExpect(jsonPath("$.id").doesNotExist());

    assertThat(statusNoBanco(produtorA.usuarioId(), mesPassado)).isEqualTo("ABERTO");
    assertThat(jdbcTemplate.queryForObject("SELECT reaberto_por FROM apuracao_mensal WHERE id = ?",
        String.class, apuracaoA)).isEqualTo(contador.usuarioId().toString());
    Map<String, Object> registro = jdbcTemplate.queryForMap("SELECT * FROM registro_auditoria"
        + " WHERE acao = 'APURACAO_REABERTA'");
    assertThat(registro.get("SUCESSO")).isEqualTo(true);
    assertThat(registro.get("RECURSO_ID")).isEqualTo(apuracaoA);
    assertThat(registro.get("PRODUTOR_ID")).isEqualTo(produtorA.usuarioId());
    assertThat(registro.get("USUARIO_ID")).isEqualTo(contador.usuarioId());
    assertThat(registro.get("PAPEL")).isEqualTo("CONTADOR");
    assertThat(registro.get("ESTADO_DEPOIS")).isEqualTo("ABERTO");
  }

  @Test
  @DisplayName("funcao CONTADOR reabre; ASSISTENTE recebe 403 e o periodo segue FECHADO")
  void funcoesDoEscritorio() throws Exception {
    funcao("ASSISTENTE");
    mockMvc.perform(post(ROTA, produtorA.usuarioId(), mesPassado)
            .header(HttpHeaders.AUTHORIZATION, tokenContador))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errors[0].code").value("PAPEL_NAO_AUTORIZADO"));
    assertThat(statusNoBanco(produtorA.usuarioId(), mesPassado)).isEqualTo("FECHADO");

    funcao("CONTADOR");
    mockMvc.perform(post(ROTA, produtorA.usuarioId(), mesPassado)
            .header(HttpHeaders.AUTHORIZATION, tokenContador))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ABERTO"));
  }

  @Test
  @DisplayName("fora da carteira ou de outro escritorio: 404, e o periodo do outro produtor nao muda")
  void foraDaCarteira() throws Exception {
    mockMvc.perform(post(ROTA, produtorB.usuarioId(), mesPassado)
            .header(HttpHeaders.AUTHORIZATION, tokenContador))
        .andExpect(status().isNotFound());
    mockMvc.perform(post(ROTA, produtorA.usuarioId(), mesPassado)
            .header(HttpHeaders.AUTHORIZATION, tokenOutroEscritorio))
        .andExpect(status().isNotFound());
    mockMvc.perform(post(ROTA, UUID.randomUUID(), mesPassado)
            .header(HttpHeaders.AUTHORIZATION, tokenContador))
        .andExpect(status().isNotFound());

    assertThat(statusNoBanco(produtorA.usuarioId(), mesPassado)).isEqualTo("FECHADO");
    assertThat(statusNoBanco(produtorB.usuarioId(), mesPassado)).isEqualTo("FECHADO");
  }

  @Test
  @DisplayName("produtor nao reabre: 403 na rota do contador e na antiga /api/apuracao/{id}/reabrir")
  void produtorNaoReabre() throws Exception {
    mockMvc.perform(post(ROTA, produtorA.usuarioId(), mesPassado)
            .header(HttpHeaders.AUTHORIZATION, tokenA))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errors[0].code").value("PAPEL_NAO_AUTORIZADO"));
    mockMvc.perform(post("/api/apuracao/{id}/reabrir", apuracaoA)
            .header(HttpHeaders.AUTHORIZATION, tokenA))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errors[0].code").value("PAPEL_NAO_AUTORIZADO"));
    // O contador tambem nao usa a rota antiga: ela nao recebe o produtor-alvo.
    mockMvc.perform(post("/api/apuracao/{id}/reabrir", apuracaoA)
            .header(HttpHeaders.AUTHORIZATION, tokenContador))
        .andExpect(status().isForbidden());

    assertThat(statusNoBanco(produtorA.usuarioId(), mesPassado)).isEqualTo("FECHADO");
  }

  @Test
  @DisplayName("RN005: 4o mes retroativo 422, periodo nao fechado 409, sem apuracao 404, formato 400")
  void regrasDaReabertura() throws Exception {
    String quatroMeses = YearMonth.now(clock).minusMonths(4).toString();
    UUID apuracaoAntiga = fechada(produtorA.usuarioId(), quatroMeses);
    mockMvc.perform(post(ROTA, produtorA.usuarioId(), quatroMeses)
            .header(HttpHeaders.AUTHORIZATION, tokenContador))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.errors[0].code").value("REABERTURA_FORA_PRAZO"))
        .andExpect(result -> semIdInterno(result.getResponse().getContentAsString(), apuracaoAntiga));

    mockMvc.perform(post(ROTA, produtorA.usuarioId(), mesPassado)
            .header(HttpHeaders.AUTHORIZATION, tokenContador))
        .andExpect(status().isOk());
    mockMvc.perform(post(ROTA, produtorA.usuarioId(), mesPassado)
            .header(HttpHeaders.AUTHORIZATION, tokenContador))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errors[0].code").value("APURACAO_NAO_REABRIVEL"))
        .andExpect(result -> semIdInterno(result.getResponse().getContentAsString(), apuracaoA));

    String semApuracao = YearMonth.now(clock).minusMonths(2).toString();
    mockMvc.perform(post(ROTA, produtorA.usuarioId(), semApuracao)
            .header(HttpHeaders.AUTHORIZATION, tokenContador))
        .andExpect(status().isNotFound());

    mockMvc.perform(post(ROTA, produtorA.usuarioId(), "2026-13")
            .header(HttpHeaders.AUTHORIZATION, tokenContador))
        .andExpect(status().isBadRequest());
  }

  /** #289: nem o message de topo nem o errors[] trazem o UUID interno da apuracao. */
  private static void semIdInterno(String corpo, UUID apuracaoId) {
    assertThat(corpo).doesNotContain(apuracaoId.toString());
  }
}
