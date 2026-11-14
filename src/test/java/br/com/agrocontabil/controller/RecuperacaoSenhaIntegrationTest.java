package br.com.agrocontabil.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.agrocontabil.dto.CadastroRequest;
import br.com.agrocontabil.dto.EnderecoPostalRequest;
import br.com.agrocontabil.model.TipoEscritorio;
import br.com.agrocontabil.repository.RedefinicaoSenhaRepository;
import br.com.agrocontabil.security.PapelUsuario;
import br.com.agrocontabil.service.CadastroService;
import br.com.agrocontabil.service.EnvioEmail;
import br.com.agrocontabil.support.TestDatabaseCleaner;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
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

/**
 * Recuperação e troca de senha pela cadeia real (issue #209). O envio de e-mail é trocado por uma
 * captura: o teste lê o link que o titular receberia.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestDatabaseCleaner.class, RecuperacaoSenhaIntegrationTest.CapturaEmail.class})
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
@DisplayName("Recuperacao e troca de senha (#209)")
class RecuperacaoSenhaIntegrationTest {

  private static final String EMAIL = "recupera@fazenda.com.br";
  private static final String SENHA = "cavalo bateria grampo correto";
  private static final String NOVA = "outra frase bem comprida agora";
  private static final String TELEFONE = "45999990000";

  /** Links enviados, na ordem. */
  static final BlockingQueue<String> LINKS = new LinkedBlockingQueue<>();

  /** Troca o envio real por uma captura do link. */
  @TestConfiguration
  static class CapturaEmail {
    @Bean
    @Primary
    EnvioEmail envioEmailCapturado() {
      return (destinatario, link, validade) -> LINKS.add(destinatario + "|" + link);
    }
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private CadastroService cadastroService;
  @Autowired private RedefinicaoSenhaRepository redefinicaoRepository;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private DataSource dataSource;
  @Autowired private TestDatabaseCleaner cleaner;

  @BeforeEach
  void setUp() {
    cleaner.limparTudo();
    LINKS.clear();
    cadastroService.cadastrar(new CadastroRequest(PapelUsuario.PRODUTOR, "Fazenda Recupera",
        "52998224725", EMAIL, SENHA, null, null, TELEFONE, null, null));
  }

  private int esqueci(String email) throws Exception {
    return mockMvc.perform(post("/api/auth/senha/esqueci").contentType(MediaType.APPLICATION_JSON)
        .content("{\"email\":\"%s\"}".formatted(email))).andReturn().getResponse().getStatus();
  }

  /** Espera o e-mail (o pedido roda em segundo plano) e devolve o token do fragmento. */
  private String tokenDoEmail() throws Exception {
    String recebido = LINKS.poll(10, TimeUnit.SECONDS);
    assertThat(recebido).as("e-mail de redefinicao").isNotNull();
    assertThat(recebido).startsWith(EMAIL + "|http://localhost:4200/redefinir-senha#token=");
    return recebido.substring(recebido.indexOf("#token=") + "#token=".length());
  }

  private MvcResult redefinir(String token, String novaSenha) throws Exception {
    return mockMvc.perform(post("/api/auth/senha/redefinir").contentType(MediaType.APPLICATION_JSON)
        .content("{\"token\":\"%s\",\"novaSenha\":\"%s\"}".formatted(token, novaSenha)))
        .andReturn();
  }

  private MvcResult login(String senha) throws Exception {
    return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
        .content("{\"email\":\"%s\",\"senha\":\"%s\"}".formatted(EMAIL, senha))).andReturn();
  }

  private String accessToken() throws Exception {
    return objectMapper.readTree(login(SENHA).getResponse().getContentAsString())
        .get("accessToken").asText();
  }

  @Test
  @DisplayName("esqueci responde 202 com ou sem conta; so a conta recebe o link")
  void esqueciNaoRevelaConta() throws Exception {
    assertThat(esqueci("ninguem@fazenda.com.br")).isEqualTo(202);
    assertThat(esqueci("email-malformado")).isEqualTo(202);
    assertThat(LINKS.poll(1, TimeUnit.SECONDS)).isNull();

    assertThat(esqueci(EMAIL)).isEqualTo(202);
    String token = tokenDoEmail();
    // No banco, so o hash do token.
    assertThat(redefinicaoRepository.findAll()).singleElement()
        .satisfies(r -> assertThat(r.getTokenHash()).isNotEqualTo(token).hasSize(64));
  }

  @Test
  @DisplayName("redefinir troca a senha, encerra as sessoes e o link nao serve de novo")
  void redefinirFluxoCompleto() throws Exception {
    String acessoAntigo = accessToken();
    esqueci(EMAIL);
    String token = tokenDoEmail();

    MvcResult redefinido = redefinir(token, NOVA);
    assertThat(redefinido.getResponse().getStatus()).isEqualTo(204);
    assertThat(redefinido.getResponse().getHeader(HttpHeaders.SET_COOKIE)).contains("Max-Age=0");

    assertThat(login(SENHA).getResponse().getStatus()).isEqualTo(401);
    assertThat(login(NOVA).getResponse().getStatus()).isEqualTo(200);
    // O access token emitido antes da redefinicao morreu (versao de sessao avancou).
    mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + acessoAntigo))
        .andExpect(status().isUnauthorized());
    // Uso unico.
    mockMvc.perform(post("/api/auth/senha/redefinir").contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"%s\",\"novaSenha\":\"%s\"}".formatted(token, NOVA + " 2")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].code").value("TOKEN_REDEFINICAO_INVALIDO"));
  }

  @Test
  @DisplayName("senha nova fraca: 400 SENHA_FRACA e o link continua valendo")
  void senhaFracaNaoQueimaOLink() throws Exception {
    esqueci(EMAIL);
    String token = tokenDoEmail();

    MvcResult fraca = redefinir(token, "1234567890");
    assertThat(fraca.getResponse().getStatus()).isEqualTo(400);
    assertThat(fraca.getResponse().getContentAsString()).contains("SENHA_FRACA")
        .doesNotContain("1234567890");
    assertThat(redefinir(token, NOVA).getResponse().getStatus()).isEqualTo(204);
  }

  @Test
  @DisplayName("redefinir com o telefone gravado na senha nova (#521): 400 e o link segue valendo")
  void redefinirRecusaTelefone() throws Exception {
    esqueci(EMAIL);
    String token = tokenDoEmail();

    MvcResult comTelefone = redefinir(token, "lavoura 99999-0000 boa");
    assertThat(comTelefone.getResponse().getStatus()).isEqualTo(400);
    assertThat(comTelefone.getResponse().getContentAsString()).contains("SENHA_FRACA")
        .doesNotContain("99999");
    assertThat(redefinir(token, NOVA).getResponse().getStatus()).isEqualTo(204);
  }

  @Test
  @DisplayName("alterar com o CNPJ do escritorio na senha nova (#521): 400 e as sessoes seguem")
  void alterarContadorRecusaCnpjDoEscritorio() throws Exception {
    String emailContador = "contador.recupera@exemplo.com.br";
    cadastroService.cadastrar(new CadastroRequest(PapelUsuario.CONTADOR, "Patricia Moreira",
        "11144477735", emailContador, SENHA,
        new CadastroRequest.EscritorioDoCadastro("Contabilidade Campo Verde", "11444777000161",
            "4533330000", new EnderecoPostalRequest("85810000", "Rua Parana", "1200", null,
                "Centro", "4104808", "Cascavel", "PR"), "campoverde", true),
        null, null, TipoEscritorio.EMPRESARIAL, true));
    String acesso = objectMapper.readTree(mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"%s\",\"senha\":\"%s\"}".formatted(emailContador, SENHA)))
        .andReturn().getResponse().getContentAsString()).get("accessToken").asText();

    for (String senha : List.of("campo 11.444.777/0001-61 verde", "fixo 3333-0000 campo")) {
      mockMvc.perform(post("/api/auth/senha/alterar").contentType(MediaType.APPLICATION_JSON)
              .header(HttpHeaders.AUTHORIZATION, "Bearer " + acesso)
              .content("{\"senhaAtual\":\"%s\",\"novaSenha\":\"%s\"}".formatted(SENHA, senha)))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.errors[0].code").value("SENHA_FRACA"))
          .andExpect(jsonPath("$.errors[0].field").value("novaSenha"));
    }
    mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + acesso))
        .andExpect(status().isOk());
    mockMvc.perform(post("/api/auth/senha/alterar").contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + acesso)
            .content("{\"senhaAtual\":\"%s\",\"novaSenha\":\"%s\"}".formatted(SENHA, NOVA)))
        .andExpect(status().isNoContent());
  }

  @Test
  @DisplayName("pedido novo invalida o link anterior; token desconhecido ou expirado: 400")
  void linkAnteriorExpiradoOuDesconhecido() throws Exception {
    esqueci(EMAIL);
    String primeiro = tokenDoEmail();
    envelhecerPedidos(Duration.ofMinutes(2)); // fora do intervalo minimo entre links
    esqueci(EMAIL);
    String segundo = tokenDoEmail();

    assertThat(redefinir(primeiro, NOVA).getResponse().getStatus()).isEqualTo(400);
    assertThat(redefinir("token-que-nao-existe", NOVA).getResponse().getStatus()).isEqualTo(400);

    new JdbcTemplate(dataSource).update("UPDATE redefinicao_senha SET expira_em = ?",
        OffsetDateTime.now().minus(Duration.ofMinutes(1)));
    assertThat(redefinir(segundo, NOVA).getResponse().getStatus()).isEqualTo(400);
  }

  @Test
  @DisplayName("alterar: senha atual certa troca e encerra sessoes; errada da 400; sem login, 401")
  void alterar() throws Exception {
    String acesso = accessToken();

    mockMvc.perform(post("/api/auth/senha/alterar").contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + acesso)
            .content("{\"senhaAtual\":\"errada mas comprida\",\"novaSenha\":\"%s\"}".formatted(NOVA)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].code").value("SENHA_ATUAL_INCORRETA"));

    mockMvc.perform(post("/api/auth/senha/alterar").contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + acesso)
            .content("{\"senhaAtual\":\"%s\",\"novaSenha\":\"%s\"}".formatted(SENHA, NOVA)))
        .andExpect(status().isNoContent());

    mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + acesso))
        .andExpect(status().isUnauthorized());
    assertThat(login(NOVA).getResponse().getStatus()).isEqualTo(200);

    mockMvc.perform(post("/api/auth/senha/alterar").contentType(MediaType.APPLICATION_JSON)
            .content("{\"senhaAtual\":\"%s\",\"novaSenha\":\"%s\"}".formatted(NOVA, SENHA)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("alterar passa pelo bloqueio por tentativas: token roubado nao adivinha a senha atual")
  void alterarBloqueiaPorTentativas() throws Exception {
    String acesso = accessToken();
    for (int i = 0; i < 5; i++) {
      mockMvc.perform(post("/api/auth/senha/alterar").contentType(MediaType.APPLICATION_JSON)
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + acesso)
          .content("{\"senhaAtual\":\"palpite %d bem comprido\",\"novaSenha\":\"%s\"}"
              .formatted(i, NOVA)));
    }

    // Com a conta travada, nem a senha atual certa troca.
    mockMvc.perform(post("/api/auth/senha/alterar").contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + acesso)
            .content("{\"senhaAtual\":\"%s\",\"novaSenha\":\"%s\"}".formatted(SENHA, NOVA)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].code").value("SENHA_ATUAL_INCORRETA"));
  }

  @Test
  @DisplayName("redefinir pelo link destrava a conta bloqueada por tentativas")
  void redefinirDestrava() throws Exception {
    for (int i = 0; i < 5; i++) {
      login("palpite " + i + " bem comprido");
    }
    assertThat(login(SENHA).getResponse().getStatus()).isEqualTo(401);

    esqueci(EMAIL);
    redefinir(tokenDoEmail(), NOVA);

    assertThat(login(NOVA).getResponse().getStatus()).isEqualTo(200);
  }

  /** Recua o criado_em dos pedidos (o intervalo mínimo entre links é de 1 minuto). */
  private void envelhecerPedidos(Duration quanto) {
    new JdbcTemplate(dataSource).update("UPDATE redefinicao_senha SET criado_em = ?",
        OffsetDateTime.now().minus(quanto));
  }

  @Test
  @DisplayName("intervalo minimo: segundo pedido em menos de 1 min nao gera link nem invalida o 1o")
  void intervaloMinimoEntreLinks() throws Exception {
    esqueci(EMAIL);
    String primeiro = tokenDoEmail();

    assertThat(esqueci(EMAIL)).isEqualTo(202);
    assertThat(LINKS.poll(1, TimeUnit.SECONDS)).isNull();
    // O link que a vitima recebeu continua valendo: nao da para invalida-lo em sequencia.
    assertThat(redefinir(primeiro, NOVA).getResponse().getStatus()).isEqualTo(204);
  }

  @Test
  @DisplayName("alterar com senha atual certa e senha nova fraca nao trava o proprio titular")
  void alterarComSenhaNovaFracaNaoTrava() throws Exception {
    String acesso = accessToken();
    for (int i = 0; i < 6; i++) {
      mockMvc.perform(post("/api/auth/senha/alterar").contentType(MediaType.APPLICATION_JSON)
              .header(HttpHeaders.AUTHORIZATION, "Bearer " + acesso)
              .content("{\"senhaAtual\":\"%s\",\"novaSenha\":\"1234567890\"}".formatted(SENHA)))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.errors[0].code").value("SENHA_FRACA"));
    }

    assertThat(login(SENHA).getResponse().getStatus()).isEqualTo(200);
  }
}
