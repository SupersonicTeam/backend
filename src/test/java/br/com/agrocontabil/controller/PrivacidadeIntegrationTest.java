package br.com.agrocontabil.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.agrocontabil.dto.CadastroRequest;
import br.com.agrocontabil.dto.CadastroResponse;
import br.com.agrocontabil.model.Contador;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.repository.ContadorRepository;
import br.com.agrocontabil.repository.CredencialUsuarioRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.repository.SessaoRefreshRepository;
import br.com.agrocontabil.security.PapelUsuario;
import br.com.agrocontabil.service.CadastroService;
import br.com.agrocontabil.support.TestDatabaseCleaner;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/** Politica de dados e direito ao esquecimento pela cadeia real (issue #148). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestDatabaseCleaner.class)
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
@DisplayName("LGPD: politica de dados e exclusao dos dados pessoais (#148)")
class PrivacidadeIntegrationTest {

  private static final String SENHA = "cavalo bateria grampo correto";
  private static final String EMAIL = "titular@fazenda.com.br";
  private static final String CPF = "52998224725";

  @Autowired private MockMvc mockMvc;
  @Autowired private CadastroService cadastroService;
  @Autowired private ProdutorRepository produtorRepository;
  @Autowired private CredencialUsuarioRepository credencialRepository;
  @Autowired private SessaoRefreshRepository sessaoRepository;
  @Autowired private ContadorRepository contadorRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private TestDatabaseCleaner cleaner;

  private CadastroResponse titular;

  @BeforeEach
  void setUp() {
    cleaner.limparTudo();
    titular = cadastroService.cadastrar(new CadastroRequest(
        PapelUsuario.PRODUTOR, "Joaquim da Silva", CPF, EMAIL, SENHA));
  }

  private String accessToken(String email) throws Exception {
    MvcResult login = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
        .content("{\"email\":\"%s\",\"senha\":\"%s\"}".formatted(email, SENHA))).andReturn();
    return objectMapper.readTree(login.getResponse().getContentAsString())
        .get("accessToken").asText();
  }

  private MvcResult excluir(UUID id, String token, String senha) throws Exception {
    return mockMvc.perform(delete("/api/usuarios/{id}/dados", id)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"senha\":\"%s\"}".formatted(senha)))
        .andReturn();
  }

  @Test
  @DisplayName("politica de dados e publica e traz controlador, bases legais, retencao e direitos")
  void politicaPublica() throws Exception {
    mockMvc.perform(get("/api/politica-dados"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.controlador").isNotEmpty())
        .andExpect(jsonPath("$.contatoEncarregado").isNotEmpty())
        .andExpect(jsonPath("$.basesLegais.length()").value(3))
        .andExpect(jsonPath("$.retencao[0]").value(org.hamcrest.Matchers.containsString("5 anos")))
        .andExpect(jsonPath("$.comoExercer").value(
            org.hamcrest.Matchers.containsString("/api/usuarios/{id}/dados")));
  }

  @Test
  @DisplayName("exclusao: anonimiza, mantem o CPF fiscal, apaga a credencial e derruba as sessoes")
  void exclusaoCompleta() throws Exception {
    String token = accessToken(EMAIL);

    MvcResult resultado = excluir(titular.usuarioId(), token, SENHA);
    assertThat(resultado.getResponse().getStatus()).isEqualTo(204);
    assertThat(resultado.getResponse().getHeader(HttpHeaders.SET_COOKIE)).contains("Max-Age=0");

    Produtor anonimizado = produtorRepository.findById(titular.usuarioId()).orElseThrow();
    assertThat(anonimizado.getNomeCompleto()).isEqualTo("Titular excluido (LGPD)");
    assertThat(anonimizado.getEmail()).isNull();
    assertThat(anonimizado.getTelefone()).isNull();
    // Guarda fiscal: o documento continua identificando o contribuinte nas notas guardadas.
    assertThat(anonimizado.getCpfCnpj()).isEqualTo(CPF);

    assertThat(credencialRepository.findByEmail(EMAIL)).isEmpty();
    // O token emitido antes morre: a credencial nao existe mais.
    mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isUnauthorized());
    assertThat(sessaoRepository.findAll()).allSatisfy(s -> assertThat(s.getRevogadoEm()).isNotNull());
    // E nao entra mais.
    mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"%s\",\"senha\":\"%s\"}".formatted(EMAIL, SENHA)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("id de outro titular: 404 e nada e apagado (anti-IDOR)")
  void outroTitular() throws Exception {
    CadastroResponse outro = cadastroService.cadastrar(new CadastroRequest(
        PapelUsuario.PRODUTOR, "Maria Souza", "11144477735", "maria@fazenda.com.br", SENHA));

    MvcResult resultado = excluir(outro.usuarioId(), accessToken(EMAIL), SENHA);

    assertThat(resultado.getResponse().getStatus()).isEqualTo(404);
    assertThat(credencialRepository.findByEmail("maria@fazenda.com.br")).isPresent();
    assertThat(produtorRepository.findById(outro.usuarioId()).orElseThrow().getNomeCompleto())
        .isEqualTo("Maria Souza");
  }

  @Test
  @DisplayName("senha errada: 400 SENHA_ATUAL_INCORRETA e nada e apagado; sem login: 401")
  void senhaErradaOuSemLogin() throws Exception {
    String token = accessToken(EMAIL);

    mockMvc.perform(delete("/api/usuarios/{id}/dados", titular.usuarioId())
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"senha\":\"errada mas bem comprida\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].code").value("SENHA_ATUAL_INCORRETA"));
    assertThat(credencialRepository.findByEmail(EMAIL)).isPresent();

    mockMvc.perform(delete("/api/usuarios/{id}/dados", titular.usuarioId())
            .contentType(MediaType.APPLICATION_JSON).content("{\"senha\":\"%s\"}".formatted(SENHA)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("contador: anonimizado, sem documento nem CRC, e sem carteira")
  void exclusaoDoContador() throws Exception {
    CadastroResponse contador = cadastroService.cadastrar(new CadastroRequest(
        PapelUsuario.CONTADOR, "Escritorio Campo", "11222333000181", "esc@campo.com.br", SENHA));
    jdbcTemplate.update("UPDATE contadores SET crc = 'MT-012345/O' WHERE id = ?",
        contador.usuarioId());
    vincular(contador.usuarioId(), titular.usuarioId());

    MvcResult resultado = excluir(contador.usuarioId(), accessToken("esc@campo.com.br"), SENHA);

    assertThat(resultado.getResponse().getStatus()).isEqualTo(204);
    assertThat(credencialRepository.findByEmail("esc@campo.com.br")).isEmpty();
    Contador anonimizado = contadorRepository.findById(contador.usuarioId()).orElseThrow();
    assertThat(anonimizado.getNomeCompleto()).isEqualTo("Titular excluido (LGPD)");
    assertThat(anonimizado.getCpfCnpj()).isNull();
    assertThat(anonimizado.getCrc()).isNull();
    assertThat(contadorRepository.existsByIdAndProdutoresId(
        contador.usuarioId(), titular.usuarioId())).isFalse();
  }

  @Test
  @DisplayName("produtor excluido: o contador da carteira deixa de alcancar a conta (404)")
  void contadorPerdeAcessoAoProdutorExcluido() throws Exception {
    CadastroResponse contador = cadastroService.cadastrar(new CadastroRequest(
        PapelUsuario.CONTADOR, "Escritorio Campo", "11222333000181", "esc@campo.com.br", SENHA));
    vincular(contador.usuarioId(), titular.usuarioId());
    String tokenContador = accessToken("esc@campo.com.br");
    mockMvc.perform(get("/api/contador/guias").param("produtorId", titular.usuarioId().toString())
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenContador))
        .andExpect(status().isOk());

    assertThat(excluir(titular.usuarioId(), accessToken(EMAIL), SENHA).getResponse().getStatus())
        .isEqualTo(204);

    mockMvc.perform(get("/api/contador/guias").param("produtorId", titular.usuarioId().toString())
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenContador))
        .andExpect(status().isNotFound());
    // O escritorio continua com a propria conta.
    assertThat(credencialRepository.findByEmail("esc@campo.com.br")).isPresent();
  }

  private void vincular(UUID contadorId, UUID produtorId) {
    jdbcTemplate.update("INSERT INTO contador_produtor (contador_id, produtor_id) VALUES (?, ?)",
        contadorId, produtorId);
  }
}
