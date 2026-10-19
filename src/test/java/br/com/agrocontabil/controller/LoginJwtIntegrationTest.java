package br.com.agrocontabil.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.agrocontabil.dto.CadastroRequest;
import br.com.agrocontabil.dto.CadastroResponse;
import br.com.agrocontabil.model.CredencialUsuario;
import br.com.agrocontabil.repository.CredencialUsuarioRepository;
import br.com.agrocontabil.security.PapelUsuario;
import br.com.agrocontabil.service.CadastroService;
import br.com.agrocontabil.support.TestDatabaseCleaner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Login e JWT pela cadeia de filtros real (issue #206).
 *
 * <p>Profile {@code test}, sem o {@code MockIdentidadeFilter}: é o cenário de produção, em que o
 * token é a única forma de autenticar.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestDatabaseCleaner.class)
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
@DisplayName("POST /api/auth/login + Authorization: Bearer (#206)")
class LoginJwtIntegrationTest {

  private static final String SENHA = "cavalo bateria grampo correto";
  private static final String EMAIL_PRODUTOR = "produtor.login@fazenda.com.br";
  private static final String EMAIL_CONTADOR = "contador.login@escritorio.com.br";

  @Autowired private MockMvc mockMvc;
  @Autowired private CadastroService cadastroService;
  @Autowired private CredencialUsuarioRepository credencialRepository;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private TestDatabaseCleaner cleaner;

  private CadastroResponse produtor;

  @BeforeEach
  void setUp() {
    cleaner.limparTudo();
    produtor = cadastroService.cadastrar(new CadastroRequest(
        PapelUsuario.PRODUTOR, "Fazenda Login", "52998224725", EMAIL_PRODUTOR, SENHA));
    cadastroService.cadastrar(new CadastroRequest(
        PapelUsuario.CONTADOR, "Escritorio Login", "11222333000181", EMAIL_CONTADOR, SENHA));
  }

  private MvcResult login(String email, String senha) throws Exception {
    return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"%s\",\"senha\":\"%s\"}".formatted(email, senha)))
        .andReturn();
  }

  private String token(String email) throws Exception {
    MvcResult resultado = login(email, SENHA);
    assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
    JsonNode corpo = objectMapper.readTree(resultado.getResponse().getContentAsString());
    return corpo.get("accessToken").asText();
  }

  private static String bearer(String token) {
    return "Bearer " + token;
  }

  @Test
  @DisplayName("login valido: 200 com token Bearer, sem cache, e o token autentica o /me")
  void loginEMe() throws Exception {
    mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"Produtor.Login@Fazenda.com.br\",\"senha\":\"%s\"}"
                .formatted(SENHA)))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, Matchers.containsString("no-store")))
        .andExpect(jsonPath("$.tokenType").value("Bearer"))
        .andExpect(jsonPath("$.papel").value("PRODUTOR"))
        .andExpect(jsonPath("$.usuarioId").value(produtor.usuarioId().toString()))
        .andExpect(jsonPath("$.expiraEm").isNotEmpty());

    mockMvc.perform(get("/api/auth/me")
            .header(HttpHeaders.AUTHORIZATION, bearer(token(EMAIL_PRODUTOR))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.usuarioId").value(produtor.usuarioId().toString()))
        .andExpect(jsonPath("$.papel").value("PRODUTOR"))
        .andExpect(jsonPath("$.nome").value("Fazenda Login"));
  }

  @Test
  @DisplayName("esquema case-insensitive: 'bearer' minusculo tambem autentica")
  void esquemaMinusculo() throws Exception {
    mockMvc.perform(get("/api/auth/me")
            .header(HttpHeaders.AUTHORIZATION, "bearer " + token(EMAIL_PRODUTOR)))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("token do contador leva o papel CONTADOR: barrado em endpoint so de produtor")
  void papelDoToken() throws Exception {
    String contador = token(EMAIL_CONTADOR);
    mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(contador)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.papel").value("CONTADOR"));
    mockMvc.perform(get("/api/alertas").header(HttpHeaders.AUTHORIZATION, bearer(contador)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errors[0].code").value("PAPEL_NAO_AUTORIZADO"));
  }

  @Test
  @DisplayName("senha errada e e-mail sem conta: o mesmo 401 CREDENCIAIS_INVALIDAS")
  void falhasIndistinguiveis() throws Exception {
    MvcResult senhaErrada = login(EMAIL_PRODUTOR, "outra senha qualquer longa");
    MvcResult semConta = login("ninguem@fazenda.com.br", SENHA);

    assertThat(senhaErrada.getResponse().getStatus()).isEqualTo(401);
    assertThat(semConta.getResponse().getStatus()).isEqualTo(401);
    String corpoA = senhaErrada.getResponse().getContentAsString()
        .replaceAll("\"timestamp\":\"[^\"]*\"", "");
    String corpoB = semConta.getResponse().getContentAsString()
        .replaceAll("\"timestamp\":\"[^\"]*\"", "");
    assertThat(corpoA).isEqualTo(corpoB).contains("CREDENCIAIS_INVALIDAS")
        .doesNotContain(EMAIL_PRODUTOR, SENHA);
  }

  @Test
  @DisplayName("e-mail malformado recebe o mesmo 401, nao um 400 que diferencie")
  void emailMalformado() throws Exception {
    assertThat(login("nao-e-email", SENHA).getResponse().getStatus()).isEqualTo(401);
  }

  @Test
  @DisplayName("token adulterado: 401 invalid_token, sem cair em identidade nenhuma")
  void tokenAdulterado() throws Exception {
    String token = token(EMAIL_PRODUTOR);
    String adulterado = token.substring(0, token.length() - 4) + "AAAA";

    mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(adulterado)))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE,
            Matchers.containsString("error=\"invalid_token\"")))
        .andExpect(jsonPath("$.errors[0].code").value("NAO_AUTENTICADO"));
  }

  @Test
  @DisplayName("versao de sessao incrementada (troca de senha) derruba o token na hora")
  void revogacaoPorVersao() throws Exception {
    String token = token(EMAIL_PRODUTOR);
    CredencialUsuario credencial = credencialRepository.findByEmail(EMAIL_PRODUTOR).orElseThrow();
    credencial.setVersaoSessao(credencial.getVersaoSessao() + 1);
    credencialRepository.save(credencial);

    mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isUnauthorized());
    // Um login novo recebe a versao atual e volta a valer.
    mockMvc.perform(get("/api/auth/me")
            .header(HttpHeaders.AUTHORIZATION, bearer(token(EMAIL_PRODUTOR))))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("credencial desativada: token ja emitido deixa de valer e o login e recusado")
  void desativada() throws Exception {
    String token = token(EMAIL_PRODUTOR);
    CredencialUsuario credencial = credencialRepository.findByEmail(EMAIL_PRODUTOR).orElseThrow();
    credencial.setAtivo(false);
    credencialRepository.save(credencial);

    mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isUnauthorized());
    assertThat(login(EMAIL_PRODUTOR, SENHA).getResponse().getStatus()).isEqualTo(401);
  }

  @Test
  @DisplayName("sem token, /me responde 401 com desafio Bearer")
  void semToken() throws Exception {
    mockMvc.perform(get("/api/auth/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, Matchers.startsWith("Bearer ")));
  }
}
