package br.com.agrocontabil.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.agrocontabil.config.RefreshProperties;
import br.com.agrocontabil.dto.CadastroRequest;
import br.com.agrocontabil.model.CredencialUsuario;
import br.com.agrocontabil.model.SessaoRefresh;
import br.com.agrocontabil.repository.CredencialUsuarioRepository;
import br.com.agrocontabil.repository.SessaoRefreshRepository;
import br.com.agrocontabil.security.PapelUsuario;
import br.com.agrocontabil.service.CadastroService;
import br.com.agrocontabil.service.SessaoRefreshLimpezaJob;
import br.com.agrocontabil.service.SessaoService;
import br.com.agrocontabil.support.TestDatabaseCleaner;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Refresh token rotativo em cookie HttpOnly, pela cadeia de filtros real (issue #207).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestDatabaseCleaner.class)
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
@DisplayName("Refresh token: rotacao, reuso, logout e vida maxima (#207)")
class RefreshTokenIntegrationTest {

  private static final String COOKIE = "agro_refresh";
  private static final String SENHA = "cavalo bateria grampo correto";
  private static final String EMAIL = "refresh@fazenda.com.br";

  @Autowired private MockMvc mockMvc;
  @Autowired private CadastroService cadastroService;
  @Autowired private SessaoService sessaoService;
  @Autowired private RefreshProperties refreshProperties;
  @Autowired private Clock clock;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private CredencialUsuarioRepository credencialRepository;
  @Autowired private SessaoRefreshRepository sessaoRepository;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private TestDatabaseCleaner cleaner;
  @Autowired private DataSource dataSource;

  @BeforeEach
  void setUp() {
    cleaner.limparTudo();
    cadastroService.cadastrar(new CadastroRequest(
        PapelUsuario.PRODUTOR, "Fazenda Refresh", "52998224725", EMAIL, SENHA));
  }

  /** Resultado de login/refresh: access token do corpo e refresh do Set-Cookie. */
  private record Par(String accessToken, String refresh, String setCookie) {
  }

  private Par login() throws Exception {
    MvcResult resultado = mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"%s\",\"senha\":\"%s\"}".formatted(EMAIL, SENHA)))
        .andExpect(status().isOk())
        .andReturn();
    return par(resultado);
  }

  private MvcResult refresh(String cookie) throws Exception {
    var requisicao = post("/api/auth/refresh");
    if (cookie != null) {
      requisicao.cookie(new Cookie(COOKIE, cookie));
    }
    return mockMvc.perform(requisicao).andReturn();
  }

  /** Move uma coluna de data da sessão para o passado (as colunas são imutáveis na entidade). */
  private void envelhecer(String coluna, SessaoRefresh sessao, OffsetDateTime quando) {
    new JdbcTemplate(dataSource).update(
        "UPDATE sessao_refresh SET " + coluna + " = ? WHERE id = ?", quando, sessao.getId());
  }

  private Par par(MvcResult resultado) throws Exception {
    String setCookie = resultado.getResponse().getHeader(HttpHeaders.SET_COOKIE);
    String valor = setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));
    String access = objectMapper.readTree(resultado.getResponse().getContentAsString())
        .get("accessToken").asText();
    return new Par(access, valor, setCookie);
  }

  @Test
  @DisplayName("login grava o refresh num cookie HttpOnly, Secure, SameSite=Strict, Path=/api/auth")
  void cookieDoLogin() throws Exception {
    MvcResult resultado = mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"%s\",\"senha\":\"%s\"}".formatted(EMAIL, SENHA)))
        .andExpect(status().isOk())
        .andReturn();
    Par par = par(resultado);

    assertThat(par.setCookie()).contains("HttpOnly", "Secure", "SameSite=Strict",
        "Path=/api/auth");
    // 7 dias (604800 s), medidos pelo relogio: o segundo corrente pode ter passado.
    assertThat(par.setCookie()).containsPattern("Max-Age=60(4800|479[0-9])");
    // O refresh nunca vai no corpo: o JavaScript da pagina nao tem acesso a ele.
    assertThat(resultado.getResponse().getContentAsString()).doesNotContain(par.refresh());
    // No banco, so o hash.
    assertThat(sessaoRepository.findAll()).singleElement()
        .satisfies(s -> assertThat(s.getTokenHash()).isNotEqualTo(par.refresh()).hasSize(64));
  }

  @Test
  @DisplayName("refresh troca o par: access novo valido e cookie novo, diferente do usado")
  void rotacao() throws Exception {
    Par primeiro = login();

    MvcResult resultado = refresh(primeiro.refresh());
    assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
    Par segundo = par(resultado);

    assertThat(segundo.refresh()).isNotEqualTo(primeiro.refresh());
    mockMvc.perform(get("/api/auth/me")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + segundo.accessToken()))
        .andExpect(status().isOk());
    assertThat(refresh(segundo.refresh()).getResponse().getStatus()).isEqualTo(200);
  }

  @Test
  @DisplayName("reuso de um refresh ja trocado derruba a familia inteira (deteccao de roubo)")
  void reusoRevogaFamilia() throws Exception {
    Par vitima = login();
    Par renovado = par(refresh(vitima.refresh()));

    // O token antigo volta (copia roubada): recusado...
    mockMvc.perform(post("/api/auth/refresh").cookie(new Cookie(COOKIE, vitima.refresh())))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.errors[0].code").value("SESSAO_INVALIDA"));
    // ...e o legitimo, que ninguem reusou, morre junto.
    assertThat(refresh(renovado.refresh()).getResponse().getStatus()).isEqualTo(401);
    assertThat(sessaoRepository.findAll()).allSatisfy(s -> assertThat(s.getRevogadoEm()).isNotNull());
  }

  @Test
  @DisplayName("um login novo nao e afetado pela revogacao de outra familia")
  void familiasIndependentes() throws Exception {
    Par celular = login();
    Par notebook = login();
    par(refresh(celular.refresh()));
    refresh(celular.refresh()); // reuso no celular

    assertThat(refresh(notebook.refresh()).getResponse().getStatus()).isEqualTo(200);
  }

  @Test
  @DisplayName("Bearer expirado/invalido anexado ao /refresh e ao /logout nao atrapalha (interceptor)")
  void bearerInvalidoNaoBloqueiaRefresh() throws Exception {
    Par par = login();

    mockMvc.perform(post("/api/auth/refresh")
            .header(HttpHeaders.AUTHORIZATION, "Bearer token.expirado.qualquer")
            .cookie(new Cookie(COOKIE, par.refresh())))
        .andExpect(status().isOk());
    mockMvc.perform(post("/api/auth/logout")
            .header(HttpHeaders.AUTHORIZATION, "Bearer token.expirado.qualquer"))
        .andExpect(status().isNoContent());
    // Fora das rotas de autenticacao o Bearer invalido continua derrubando a requisicao.
    mockMvc.perform(post("/api/auth/logout-todos")
            .header(HttpHeaders.AUTHORIZATION, "Bearer token.expirado.qualquer"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("sem cookie, cookie vazio ou desconhecido: 401 SESSAO_INVALIDA")
  void semCookie() throws Exception {
    assertThat(refresh(null).getResponse().getStatus()).isEqualTo(401);
    MvcResult desconhecido = refresh("nao-existe");
    assertThat(desconhecido.getResponse().getStatus()).isEqualTo(401);
    // O cookie que nao renova e apagado na mesma resposta: o navegador para de reenvia-lo.
    assertThat(desconhecido.getResponse().getHeader(HttpHeaders.SET_COOKIE))
        .contains(COOKIE + "=", "Max-Age=0");
  }

  @Test
  @DisplayName("logout revoga o refresh e apaga o cookie; e idempotente")
  void logout() throws Exception {
    Par par = login();

    MvcResult saida = mockMvc.perform(post("/api/auth/logout")
            .cookie(new Cookie(COOKIE, par.refresh())))
        .andExpect(status().isNoContent())
        .andReturn();
    assertThat(saida.getResponse().getHeader(HttpHeaders.SET_COOKIE)).contains("Max-Age=0");
    assertThat(refresh(par.refresh()).getResponse().getStatus()).isEqualTo(401);

    mockMvc.perform(post("/api/auth/logout")).andExpect(status().isNoContent());
    mockMvc.perform(post("/api/auth/logout").cookie(new Cookie(COOKIE, "lixo")))
        .andExpect(status().isNoContent());
  }

  @Test
  @DisplayName("sair de todos: revoga os refresh e derruba na hora os access ja emitidos")
  void sairDeTodos() throws Exception {
    Par a = login();
    Par b = login();

    mockMvc.perform(post("/api/auth/logout-todos")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + a.accessToken()))
        .andExpect(status().isNoContent());

    mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + b.accessToken()))
        .andExpect(status().isUnauthorized());
    assertThat(refresh(a.refresh()).getResponse().getStatus()).isEqualTo(401);
    assertThat(refresh(b.refresh()).getResponse().getStatus()).isEqualTo(401);
  }

  @Test
  @DisplayName("sair de todos exige identidade: nao e publico como o logout")
  void sairDeTodosNaoEhPublico() throws Exception {
    mockMvc.perform(post("/api/auth/logout-todos")).andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("versao de sessao incrementada (troca de senha) mata o refresh e a familia")
  void versaoIncrementada() throws Exception {
    Par par = login();
    CredencialUsuario credencial = credencialRepository.findByEmail(EMAIL).orElseThrow();
    credencial.setVersaoSessao(credencial.getVersaoSessao() + 1);
    credencialRepository.save(credencial);

    assertThat(refresh(par.refresh()).getResponse().getStatus()).isEqualTo(401);
    assertThat(sessaoRepository.findAll()).allSatisfy(s -> assertThat(s.getRevogadoEm()).isNotNull());
  }

  @Test
  @DisplayName("refresh expirado e vida maxima da sessao esgotada: 401")
  void expiracaoEVidaMaxima() throws Exception {
    Par expirado = login();
    SessaoRefresh sessao = sessaoRepository.findAll().get(0);
    // expira_em e familia_iniciada_em sao imutaveis pela entidade (updatable = false): so por SQL.
    envelhecer("expira_em", sessao, OffsetDateTime.now().minusMinutes(1));
    assertThat(refresh(expirado.refresh()).getResponse().getStatus()).isEqualTo(401);

    cleaner.limparTudo();
    setUp();
    Par antigo = login();
    SessaoRefresh familiaVelha = sessaoRepository.findAll().get(0);
    envelhecer("familia_iniciada_em", familiaVelha, OffsetDateTime.now().minusDays(31));
    assertThat(refresh(antigo.refresh()).getResponse().getStatus()).isEqualTo(401);
  }

  @Test
  @DisplayName("renovacoes simultaneas do mesmo refresh: no maximo uma passa")
  void renovacoesSimultaneas() throws Exception {
    Par par = login();
    int concorrentes = 4;
    ExecutorService pool = Executors.newFixedThreadPool(concorrentes);
    CountDownLatch largada = new CountDownLatch(1);
    try {
      List<Future<Boolean>> resultados = new ArrayList<>();
      for (int i = 0; i < concorrentes; i++) {
        resultados.add(pool.submit(() -> {
          largada.await();
          try {
            sessaoService.renovar(par.refresh());
            return true;
          } catch (br.com.agrocontabil.exception.SessaoInvalidaException e) {
            return false;
          }
        }));
      }
      largada.countDown();
      long sucessos = 0;
      for (Future<Boolean> resultado : resultados) {
        if (resultado.get(30, TimeUnit.SECONDS)) {
          sucessos++;
        }
      }
      // Os perdedores sao reuso: a familia acaba revogada, mas nunca duas renovacoes passam.
      assertThat(sucessos).isLessThanOrEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName("limpeza apaga so as sessoes vencidas ha mais que a retencao")
  void limpeza() throws Exception {
    login();
    login();
    List<SessaoRefresh> sessoes = sessaoRepository.findAll();
    envelhecer("expira_em", sessoes.get(0), OffsetDateTime.now().minusDays(8));

    // O job fica desligado no profile test (o cron nao pode disparar no meio da suite).
    SessaoRefreshLimpezaJob job =
        new SessaoRefreshLimpezaJob(sessaoRepository, refreshProperties, clock);
    new TransactionTemplate(transactionManager).executeWithoutResult(s -> job.limpar());

    assertThat(sessaoRepository.findAll()).hasSize(1);
  }
}
