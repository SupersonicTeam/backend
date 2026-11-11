package br.com.agrocontabil.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.agrocontabil.dto.CadastroRequest;
import br.com.agrocontabil.dto.CadastroResponse;
import br.com.agrocontabil.security.PapelUsuario;
import br.com.agrocontabil.service.CadastroService;
import br.com.agrocontabil.support.TestDatabaseCleaner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.Cookie;
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
import org.springframework.test.web.servlet.ResultActions;

/**
 * Login e refresh pelo endereço do escritório (issue #464), pela cadeia real. O host chega pelo
 * {@code Host} da requisição (o MockMvc o põe no {@code getServerName()}, como o Tomcat). O host só
 * restringe: no principal, nada muda.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestDatabaseCleaner.class)
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
@DisplayName("Login e refresh no subdominio do escritorio: so membro entra; www nao muda (#464)")
class LoginSubdominioIntegrationTest {

  private static final String SENHA = "cavalo bateria grampo correto";
  private static final String HOST_ESCRITORIO = "ruraloeste.agroconia.com.br";
  private static final String HOST_WWW = "www.agroconia.com.br";
  private static final String EMAIL_ADMIN = "admin@rural-oeste.com.br";
  private static final String EMAIL_OUTRO = "admin@campo-verde.com.br";
  private static final String EMAIL_PRODUTOR = "joaquim@fazenda.com.br";

  @Autowired private MockMvc mockMvc;
  @Autowired private CadastroService cadastroService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private TestDatabaseCleaner cleaner;

  private CadastroResponse admin;

  @BeforeEach
  void setUp() {
    cleaner.limparTudo();
    admin = cadastroService.cadastrar(new CadastroRequest(
        PapelUsuario.CONTADOR, "Rural Oeste", "52998224725", EMAIL_ADMIN, SENHA));
    cadastroService.cadastrar(new CadastroRequest(
        PapelUsuario.CONTADOR, "Campo Verde", "39053344705", EMAIL_OUTRO, SENHA));
    cadastroService.cadastrar(new CadastroRequest(
        PapelUsuario.PRODUTOR, "Joaquim", "86288366757", EMAIL_PRODUTOR, SENHA));
    jdbcTemplate.update("UPDATE escritorio SET slug = 'ruraloeste', cnpj = '11222333000181',"
        + " cnpj_verificado_em = CURRENT_TIMESTAMP, cnpj_verificacao_metodo = 'MANUAL'"
        + " WHERE id = (SELECT escritorio_id FROM contadores WHERE id = ?)", admin.usuarioId());
  }

  private ResultActions login(String host, String email, String senha, String escritorio)
      throws Exception {
    String corpo = escritorio == null
        ? "{\"email\":\"%s\",\"senha\":\"%s\"}".formatted(email, senha)
        : "{\"email\":\"%s\",\"senha\":\"%s\",\"escritorio\":\"%s\"}"
            .formatted(email, senha, escritorio);
    return mockMvc.perform(post("/api/auth/login").header(HttpHeaders.HOST, host)
        .contentType(MediaType.APPLICATION_JSON).content(corpo));
  }

  private JsonNode corpo(ResultActions resposta) throws Exception {
    JsonNode no = objectMapper.readTree(resposta.andReturn().getResponse().getContentAsString());
    ((ObjectNode) no).remove("timestamp");
    return no;
  }

  private int falhasDe(String email) {
    return jdbcTemplate.queryForObject("SELECT falhas_login FROM credencial_usuario c"
        + " WHERE c.id = ?", Integer.class, credencialDe(email));
  }

  /** E-mail cifrado no banco: a credencial do administrador sai pelo id da identidade. */
  private UUID credencialDe(String email) {
    assertThat(email).isEqualTo(EMAIL_ADMIN);
    return jdbcTemplate.queryForObject(
        "SELECT id FROM credencial_usuario WHERE contador_id = ?", UUID.class, admin.usuarioId());
  }

  private static String refreshDe(MvcResult resultado) {
    String setCookie = resultado.getResponse().getHeader(HttpHeaders.SET_COOKIE);
    return setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));
  }

  @Test
  @DisplayName("www e host fora do dominio, sem escritorio: login de todos como hoje")
  void principalNaoMuda() throws Exception {
    for (String host : new String[] {HOST_WWW, "agroconia.com.br", "localhost", "10.0.0.5"}) {
      login(host, EMAIL_PRODUTOR, SENHA, null).andExpect(status().isOk());
      login(host, EMAIL_ADMIN, SENHA, null).andExpect(status().isOk());
      login(host, EMAIL_OUTRO, SENHA, null).andExpect(status().isOk());
    }
  }

  @Test
  @DisplayName("subdominio: o membro entra; produtor, outro escritorio e slug errado recebem o 401 de sempre")
  void soMembroEntra() throws Exception {
    login(HOST_ESCRITORIO, EMAIL_ADMIN, SENHA, "ruraloeste").andExpect(status().isOk())
        .andExpect(jsonPath("$.papel").value("CONTADOR"));
    // Sem o campo, o host decide sozinho.
    login(HOST_ESCRITORIO, EMAIL_ADMIN, SENHA, null).andExpect(status().isOk());

    JsonNode senhaErrada = corpo(login(HOST_WWW, EMAIL_ADMIN, "senha errada mas comprida", null)
        .andExpect(status().isUnauthorized()));
    for (ResultActions recusa : new ResultActions[] {
        login(HOST_ESCRITORIO, EMAIL_PRODUTOR, SENHA, "ruraloeste"),
        login(HOST_ESCRITORIO, EMAIL_OUTRO, SENHA, "ruraloeste"),
        login(HOST_ESCRITORIO, EMAIL_ADMIN, SENHA, "outro"),
        login("inexistente.agroconia.com.br", EMAIL_ADMIN, SENHA, "inexistente"),
        login("x.ruraloeste.agroconia.com.br", EMAIL_ADMIN, SENHA, null),
        login(HOST_WWW, EMAIL_ADMIN, SENHA, "ruraloeste"),
        login(HOST_ESCRITORIO, EMAIL_ADMIN, SENHA, "Ruraloeste !")}) {
      recusa.andExpect(status().isUnauthorized())
          .andExpect(header().exists(HttpHeaders.WWW_AUTHENTICATE));
      assertThat(corpo(recusa)).isEqualTo(senhaErrada);
    }
  }

  @Test
  @DisplayName("subdominio: escritorio sem CNPJ nao e servido, nem ao membro")
  void semCnpjNaoServe() throws Exception {
    jdbcTemplate.update("UPDATE escritorio SET cnpj = NULL, cnpj_verificado_em = NULL,"
        + " cnpj_verificacao_metodo = NULL WHERE slug = 'ruraloeste'");
    login(HOST_ESCRITORIO, EMAIL_ADMIN, SENHA, "ruraloeste").andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("subdominio: CNPJ nao verificado nao e servido, nem ao membro; o www segue (#520)")
  void naoVerificadoNaoServe() throws Exception {
    jdbcTemplate.update("UPDATE escritorio SET cnpj_verificado_em = NULL,"
        + " cnpj_verificacao_metodo = NULL WHERE slug = 'ruraloeste'");
    login(HOST_ESCRITORIO, EMAIL_ADMIN, SENHA, "ruraloeste").andExpect(status().isUnauthorized());
    login(HOST_ESCRITORIO, EMAIL_ADMIN, SENHA, null).andExpect(status().isUnauthorized());
    login(HOST_WWW, EMAIL_ADMIN, SENHA, null).andExpect(status().isOk());
  }

  @Test
  @DisplayName("refresh no subdominio: a verificacao revogada derruba a familia (401) (#520)")
  void refreshSemVerificacao() throws Exception {
    MvcResult entrada = login(HOST_ESCRITORIO, EMAIL_ADMIN, SENHA, "ruraloeste")
        .andExpect(status().isOk()).andReturn();
    jdbcTemplate.update("UPDATE escritorio SET cnpj_verificado_em = NULL,"
        + " cnpj_verificacao_metodo = NULL WHERE slug = 'ruraloeste'");
    mockMvc.perform(post("/api/auth/refresh").header(HttpHeaders.HOST, HOST_ESCRITORIO)
            .cookie(new Cookie("agro_refresh", refreshDe(entrada))))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.errors[0].code").value("SESSAO_INVALIDA"));
  }

  @Test
  @DisplayName("senha certa fora do escritorio zera as falhas; senha errada no subdominio conta como sempre")
  void forcaBrutaNoSubdominio() throws Exception {
    for (int i = 0; i < 4; i++) {
      login(HOST_ESCRITORIO, EMAIL_ADMIN, "senha errada mas comprida", "ruraloeste")
          .andExpect(status().isUnauthorized());
    }
    assertThat(falhasDe(EMAIL_ADMIN)).isEqualTo(4);
    // Membro de outro endereco, com a senha certa: 401, mas nao e atacante — as falhas zeram.
    login("outro.agroconia.com.br", EMAIL_ADMIN, SENHA, null).andExpect(status().isUnauthorized());
    assertThat(falhasDe(EMAIL_ADMIN)).isZero();
    login(HOST_ESCRITORIO, EMAIL_ADMIN, SENHA, "ruraloeste").andExpect(status().isOk());
  }

  @Test
  @DisplayName("refresh no subdominio: membro renova; quem saiu do escritorio perde a familia (401)")
  void refreshReconfereOMembro() throws Exception {
    MvcResult entrada = login(HOST_ESCRITORIO, EMAIL_ADMIN, SENHA, "ruraloeste")
        .andExpect(status().isOk()).andReturn();
    MvcResult renovado = mockMvc.perform(post("/api/auth/refresh")
            .header(HttpHeaders.HOST, HOST_ESCRITORIO)
            .cookie(new Cookie("agro_refresh", refreshDe(entrada))))
        .andExpect(status().isOk()).andReturn();

    // O escritorio perde o endereco (ou o membro sai): a proxima renovacao no subdominio cai.
    jdbcTemplate.update("UPDATE escritorio SET slug = NULL WHERE slug = 'ruraloeste'");
    mockMvc.perform(post("/api/auth/refresh").header(HttpHeaders.HOST, HOST_ESCRITORIO)
            .cookie(new Cookie("agro_refresh", refreshDe(renovado))))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.errors[0].code").value("SESSAO_INVALIDA"));
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sessao_refresh WHERE"
        + " revogado_em IS NULL", Integer.class)).isZero();
  }

  @Test
  @DisplayName("refresh no host principal nao confere escritorio: produtor renova como hoje")
  void refreshPrincipalNaoMuda() throws Exception {
    MvcResult entrada = login(HOST_WWW, EMAIL_PRODUTOR, SENHA, null)
        .andExpect(status().isOk()).andReturn();
    mockMvc.perform(post("/api/auth/refresh").header(HttpHeaders.HOST, HOST_WWW)
            .cookie(new Cookie("agro_refresh", refreshDe(entrada))))
        .andExpect(status().isOk());
  }
}
