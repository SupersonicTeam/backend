package br.com.agrocontabil.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.support.IdentidadeMockadaConfig;
import br.com.agrocontabil.support.TestDatabaseCleaner;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Proteções básicas da API (issue #150): limite geral por identidade/IP, limite do chat, headers de
 * segurança, CORS restrito e Content-Type. Limites baixos só nesta classe.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({IdentidadeMockadaConfig.class, TestDatabaseCleaner.class})
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "app.seguranca.limite.geral-por-minuto=5",
    "app.seguranca.limite.chat-por-minuto=2"})
@Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
@DisplayName("Protecao basica da API: limite geral, chat, headers e CORS (#150)")
class ProtecaoApiIntegrationTest {

  private static final AtomicInteger PROXIMO_IP = new AtomicInteger(1);

  @Autowired private MockMvc mockMvc;
  @Autowired private ProdutorRepository produtorRepository;
  @Autowired private TestDatabaseCleaner cleaner;

  private Produtor produtorA;
  private Produtor produtorB;

  @BeforeEach
  void setUp() {
    cleaner.limparTudo();
    produtorA = produtorRepository.save(Produtor.builder()
        .nomeCompleto("Fazenda A").cpfCnpj(DataLoader.CPF_PRODUTOR_SEMENTE).build());
    produtorB = produtorRepository.save(Produtor.builder()
        .nomeCompleto("Fazenda B").cpfCnpj("98765432100").build());
  }

  /** IP novo por teste: os baldes do limitador vivem enquanto o contexto viver. */
  private static String ipNovo() {
    return "10.9." + (PROXIMO_IP.get() / 250) + "." + (PROXIMO_IP.getAndIncrement() % 250 + 1);
  }

  private static MockHttpServletRequestBuilder doIp(MockHttpServletRequestBuilder req, String ip) {
    return req.with(r -> {
      r.setRemoteAddr(ip);
      return r;
    });
  }

  @Test
  @DisplayName("sem identidade, o limite e por IP: a 6a chamada no minuto recebe 429")
  void limitePorIp() throws Exception {
    String ip = ipNovo();
    for (int i = 0; i < 5; i++) {
      mockMvc.perform(doIp(get("/api/v1/health"), ip)).andExpect(status().isOk());
    }
    mockMvc.perform(doIp(get("/api/v1/health"), ip))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
        .andExpect(jsonPath("$.errors[0].code").value("MUITAS_TENTATIVAS"));
    // Rota fora de /api nao conta.
    mockMvc.perform(doIp(get("/v3/api-docs"), ip)).andExpect(status().isOk());
  }

  @Test
  @DisplayName("com identidade, o limite e por usuario: outro usuario no mesmo IP nao e afetado")
  void limitePorIdentidade() throws Exception {
    String ip = ipNovo();
    for (int i = 0; i < 5; i++) {
      mockMvc.perform(doIp(get("/api/auth/me"), ip)
              .header(MockIdentidadeFilter.HEADER_USUARIO_ID, produtorB.getId().toString()))
          .andExpect(status().isOk());
    }
    mockMvc.perform(doIp(get("/api/auth/me"), ip)
            .header(MockIdentidadeFilter.HEADER_USUARIO_ID, produtorB.getId().toString()))
        .andExpect(status().isTooManyRequests());

    // Mesmo IP (escritorio atras de NAT), outro usuario: cota propria.
    mockMvc.perform(doIp(get("/api/auth/me"), ip)
            .header(MockIdentidadeFilter.HEADER_USUARIO_ID, produtorA.getId().toString()))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("chat da Ceres: limite proprio, menor que o geral")
  void limiteDoChat() throws Exception {
    String ip = ipNovo();
    for (int i = 0; i < 2; i++) {
      // Corpo invalido: o 400 vem do controller, depois de o limite ja ter contado.
      mockMvc.perform(doIp(post("/api/assistente/chat"), ip)
              .header(MockIdentidadeFilter.HEADER_USUARIO_ID, produtorA.getId().toString())
              .contentType(MediaType.APPLICATION_JSON).content("{}"))
          .andExpect(status().isBadRequest());
    }
    mockMvc.perform(doIp(post("/api/assistente/chat"), ip)
            .header(MockIdentidadeFilter.HEADER_USUARIO_ID, produtorA.getId().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isTooManyRequests());
  }

  @Test
  @DisplayName("headers de seguranca: nosniff, frame DENY, no-referrer, Permissions-Policy, CSP e HSTS")
  void headersDeSeguranca() throws Exception {
    mockMvc.perform(doIp(get("/api/v1/health"), ipNovo()).secure(true))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(header().string("X-Frame-Options", "DENY"))
        .andExpect(header().string("Referrer-Policy", "no-referrer"))
        .andExpect(header().string("Permissions-Policy", Matchers.containsString("camera=()")))
        .andExpect(header().string("Content-Security-Policy",
            Matchers.containsString("default-src 'none'")))
        .andExpect(header().string("Strict-Transport-Security",
            Matchers.allOf(Matchers.containsString("max-age=31536000"),
                Matchers.containsString("includeSubDomains"))));
  }

  @Test
  @DisplayName("401 da cadeia tambem leva os headers; Swagger fica sem a CSP da API")
  void headersEmErroEForaDaApi() throws Exception {
    mockMvc.perform(doIp(get("/api/dashboard"), ipNovo())
            .header(HttpHeaders.AUTHORIZATION, "Bearer token.invalido.aqui"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(header().exists("Content-Security-Policy"));
    mockMvc.perform(doIp(get("/v3/api-docs"), ipNovo()))
        .andExpect(header().doesNotExist("Content-Security-Policy"))
        .andExpect(jsonPath("$.info.title").value("AgroConIA API")) // #336
        .andExpect(jsonPath("$.info.contact.name").value("GWNext"))
        .andExpect(jsonPath("$.info.contact.email").value("contato@gwnext.com.br"));
  }

  @Test
  @DisplayName("CORS: requisicao real de origem nao autorizada e recusada (403)")
  void corsOrigemNaoAutorizada() throws Exception {
    mockMvc.perform(doIp(get("/api/v1/health"), ipNovo())
            .header("Origin", "https://site-malicioso.example"))
        .andExpect(status().isForbidden());
    mockMvc.perform(doIp(get("/api/v1/health"), ipNovo())
            .header("Origin", "http://localhost:4200"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:4200"));
  }

  @Test
  @DisplayName("POST com Content-Type que nao e JSON: 415 (sem requisicao 'simples' de CSRF)")
  void contentTypeNaoJson() throws Exception {
    int status = mockMvc.perform(doIp(post("/api/auth/login"), ipNovo())
            .contentType(MediaType.TEXT_PLAIN).content("{\"email\":\"a@b.c\",\"senha\":\"x\"}"))
        .andReturn().getResponse().getStatus();
    assertThat(status).isEqualTo(415);
  }

  @Test
  @DisplayName("sem header, o mock cai no semente, mas o limite segue por IP")
  void semHeaderLimitaPorIpMesmoComSemente() throws Exception {
    String ipAbusivo = ipNovo();
    for (int i = 0; i < 5; i++) {
      mockMvc.perform(doIp(get("/api/auth/me"), ipAbusivo)).andExpect(status().isOk());
    }
    mockMvc.perform(doIp(get("/api/auth/me"), ipAbusivo)).andExpect(status().isTooManyRequests());

    // Outro cliente sem header, tambem resolvido como o semente: cota propria.
    mockMvc.perform(doIp(get("/api/auth/me"), ipNovo())).andExpect(status().isOk());
  }

  @Test
  @DisplayName("rotas publicas de autenticacao ficam fora do limite geral (tem limite proprio)")
  void rotasDeAutenticacaoForaDoGeral() throws Exception {
    String ip = ipNovo();
    for (int i = 0; i < 5; i++) {
      mockMvc.perform(doIp(get("/api/v1/health"), ip));
    }
    // O balde geral deste IP esgotou, mas o logout (rota de autenticacao) nao depende dele.
    mockMvc.perform(doIp(post("/api/auth/logout"), ip)).andExpect(status().isNoContent());
  }
}
