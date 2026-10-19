package br.com.agrocontabil.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.agrocontabil.dto.CadastroRequest;
import br.com.agrocontabil.model.CredencialUsuario;
import br.com.agrocontabil.repository.CredencialUsuarioRepository;
import br.com.agrocontabil.security.PapelUsuario;
import br.com.agrocontabil.service.BloqueioContaService;
import br.com.agrocontabil.service.CadastroService;
import br.com.agrocontabil.support.TestDatabaseCleaner;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Bloqueio por conta e limite por IP, pela cadeia de filtros real (issue #208).
 *
 * <p>Limite de login baixo (3/min) só nesta classe. Cada tentativa do teste de bloqueio vem de um
 * IP diferente — é o ataque distribuído que o limite por IP não pega e o bloqueio por conta pega.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestDatabaseCleaner.class)
@ActiveProfiles("test")
@TestPropertySource(properties = "app.seguranca.forca-bruta.login-por-minuto=3")
@Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
@DisplayName("Forca bruta: bloqueio por conta e limite por IP (#208)")
class ForcaBrutaIntegrationTest {

  private static final String SENHA = "cavalo bateria grampo correto";
  private static final String EMAIL = "bruta@fazenda.com.br";
  private static final AtomicInteger PROXIMO_IP = new AtomicInteger(1);

  @Autowired private MockMvc mockMvc;
  @Autowired private CadastroService cadastroService;
  @Autowired private BloqueioContaService bloqueioConta;
  @Autowired private CredencialUsuarioRepository credencialRepository;
  @Autowired private DataSource dataSource;
  @Autowired private TestDatabaseCleaner cleaner;

  @BeforeEach
  void setUp() {
    cleaner.limparTudo();
    cadastroService.cadastrar(new CadastroRequest(
        PapelUsuario.PRODUTOR, "Fazenda Bruta", "52998224725", EMAIL, SENHA));
  }

  /** Cada chamada vem de um IP novo: isola o bloqueio por conta do limite por IP. */
  private static String ipNovo() {
    return "10.0." + (PROXIMO_IP.get() / 250) + "." + (PROXIMO_IP.getAndIncrement() % 250 + 1);
  }

  private static MockHttpServletRequestBuilder login(String email, String senha, String ip) {
    return post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
        .content("{\"email\":\"%s\",\"senha\":\"%s\"}".formatted(email, senha))
        .with(requisicao -> {
          requisicao.setRemoteAddr(ip);
          return requisicao;
        });
  }

  private CredencialUsuario credencial() {
    return credencialRepository.findByEmail(EMAIL).orElseThrow();
  }

  @Test
  @DisplayName("5 senhas erradas (de IPs diferentes) travam a conta: nem a senha certa entra")
  void bloqueioPorConta() throws Exception {
    for (int i = 0; i < 5; i++) {
      mockMvc.perform(login(EMAIL, "senha errada numero " + i, ipNovo()))
          .andExpect(status().isUnauthorized());
    }

    assertThat(credencial().getFalhasLogin()).isEqualTo(5);
    assertThat(credencial().getBloqueadaAte()).isAfter(OffsetDateTime.now().plusMinutes(14));
    // Bloqueada: a senha certa recebe o mesmo 401 de qualquer erro (sem 423, que denunciaria a
    // conta).
    mockMvc.perform(login(EMAIL, SENHA, ipNovo()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.errors[0].code").value("CREDENCIAIS_INVALIDAS"));
  }

  @Test
  @DisplayName("bloqueio vencido: a senha certa entra e zera a contagem")
  void bloqueioVencido() throws Exception {
    for (int i = 0; i < 5; i++) {
      mockMvc.perform(login(EMAIL, "senha errada numero " + i, ipNovo()));
    }
    new JdbcTemplate(dataSource).update(
        "UPDATE credencial_usuario SET bloqueada_ate = ? WHERE email = ?",
        OffsetDateTime.now().minusSeconds(1), EMAIL);

    mockMvc.perform(login(EMAIL, SENHA, ipNovo())).andExpect(status().isOk());
    assertThat(credencial().getFalhasLogin()).isZero();
    assertThat(credencial().getBloqueadaAte()).isNull();
  }

  @Test
  @DisplayName("erro depois do bloqueio vencido recomeca a contagem (nao trava de novo na hora)")
  void contagemRecomeca() throws Exception {
    for (int i = 0; i < 5; i++) {
      mockMvc.perform(login(EMAIL, "senha errada numero " + i, ipNovo()));
    }
    new JdbcTemplate(dataSource).update(
        "UPDATE credencial_usuario SET bloqueada_ate = ? WHERE email = ?",
        OffsetDateTime.now().minusSeconds(1), EMAIL);

    mockMvc.perform(login(EMAIL, "mais uma errada", ipNovo()));

    assertThat(credencial().getFalhasLogin()).isEqualTo(1);
    assertThat(credencial().getBloqueadaAte()).isNull();
  }

  @Test
  @DisplayName("limite por IP: a 4a tentativa no minuto recebe 429 com Retry-After; outro IP segue")
  void limitePorIp() throws Exception {
    String ip = ipNovo();
    for (int i = 0; i < 3; i++) {
      mockMvc.perform(login("ninguem@fazenda.com.br", SENHA, ip))
          .andExpect(status().isUnauthorized());
    }

    mockMvc.perform(login("ninguem@fazenda.com.br", SENHA, ip))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string(HttpHeaders.RETRY_AFTER, Matchers.matchesPattern("\\d+")))
        .andExpect(jsonPath("$.errors[0].code").value("MUITAS_TENTATIVAS"));
    mockMvc.perform(login(EMAIL, SENHA, ipNovo())).andExpect(status().isOk());
  }

  @Test
  @DisplayName("X-Forwarded-For forjado nao troca o IP: o limite continua valendo")
  void xForwardedForNaoEnganaOLimite() throws Exception {
    String ip = ipNovo();
    for (int i = 0; i < 3; i++) {
      mockMvc.perform(login("ninguem@fazenda.com.br", SENHA, ip)
          .header("X-Forwarded-For", "203.0.113." + i));
    }

    mockMvc.perform(login("ninguem@fazenda.com.br", SENHA, ip)
            .header("X-Forwarded-For", "203.0.113.99"))
        .andExpect(status().isTooManyRequests());
  }

  @Test
  @DisplayName("falhas simultaneas da mesma conta: todas contam (incremento sob lock)")
  void falhasSimultaneas() throws Exception {
    UUID id = credencial().getId();
    int concorrentes = 8;
    ExecutorService pool = Executors.newFixedThreadPool(concorrentes);
    CountDownLatch largada = new CountDownLatch(1);
    try {
      List<Future<?>> tarefas = new ArrayList<>();
      for (int i = 0; i < concorrentes; i++) {
        tarefas.add(pool.submit(() -> {
          largada.await();
          bloqueioConta.registrarFalha(id);
          return null;
        }));
      }
      largada.countDown();
      for (Future<?> tarefa : tarefas) {
        tarefa.get(30, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(credencial().getFalhasLogin()).isEqualTo(concorrentes);
  }
}
