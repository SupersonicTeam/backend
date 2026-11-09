package br.com.agrocontabil.config;

import static org.springframework.http.HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS;
import static org.springframework.http.HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS;
import static org.springframework.http.HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS;
import static org.springframework.http.HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN;
import static org.springframework.http.HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS;
import static org.springframework.http.HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS;
import static org.springframework.http.HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD;
import static org.springframework.http.HttpHeaders.ORIGIN;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Contrato de CORS para o frontend Angular em {@code http://localhost:4200}
 * (issue Agrocontabil#66 — Sprint 5).
 *
 * <p>Trava o comportamento que o Angular depende para consumir a API em {@code :8080}:
 * preflight aceito para a origem permitida, métodos liberados, credenciais habilitadas,
 * {@code Content-Disposition} exposto (downloads de XML/PDF) e origem desconhecida rejeitada.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CorsIntegrationTest {

  private static final String ORIGEM_ANGULAR = "http://localhost:4200";

  @Autowired
  private MockMvc mockMvc;

  @Test
  @DisplayName("Preflight da origem do Angular e aceito com metodos e credenciais")
  void preflightDaOrigemAngularAceito() throws Exception {
    mockMvc.perform(options("/api/notas")
            .header(ORIGIN, ORIGEM_ANGULAR)
            .header(ACCESS_CONTROL_REQUEST_METHOD, "POST")
            .header(ACCESS_CONTROL_REQUEST_HEADERS, "Content-Type"))
        .andExpect(status().isOk())
        .andExpect(header().string(ACCESS_CONTROL_ALLOW_ORIGIN, ORIGEM_ANGULAR))
        .andExpect(header().string(ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"))
        .andExpect(header().string(ACCESS_CONTROL_ALLOW_METHODS,
            Matchers.stringContainsInOrder("GET", "POST", "PUT", "PATCH", "DELETE")));
  }

  @Test
  @DisplayName("Resposta real para a origem do Angular expoe Content-Disposition (downloads)")
  void respostaExpoeContentDispositionParaDownloads() throws Exception {
    mockMvc.perform(get("/api/v1/health").header(ORIGIN, ORIGEM_ANGULAR))
        .andExpect(status().isOk())
        .andExpect(header().string(ACCESS_CONTROL_ALLOW_ORIGIN, ORIGEM_ANGULAR))
        .andExpect(header().string(ACCESS_CONTROL_EXPOSE_HEADERS,
            Matchers.containsString("Content-Disposition")));
  }

  @Test
  @DisplayName("Resposta expoe X-Total-Count (total da listagem paginada de guias, #103)")
  void respostaExpoeTotalDaListagemPaginada() throws Exception {
    // Sem o header na lista de expostos, o navegador esconde o total do Angular mesmo com 200.
    mockMvc.perform(get("/api/v1/health").header(ORIGIN, ORIGEM_ANGULAR))
        .andExpect(status().isOk())
        .andExpect(header().string(ACCESS_CONTROL_EXPOSE_HEADERS,
            Matchers.containsString("X-Total-Count")));
  }

  @Test
  @DisplayName("Resposta expoe Retry-After (503 ASSISTENTE_OCUPADO da Ceres, #157)")
  void respostaExpoeRetryAfterDaCeres() throws Exception {
    // Retry-After nao esta entre os headers que o navegador libera por padrao.
    mockMvc.perform(get("/api/v1/health").header(ORIGIN, ORIGEM_ANGULAR))
        .andExpect(status().isOk())
        .andExpect(header().string(ACCESS_CONTROL_EXPOSE_HEADERS,
            Matchers.containsString("Retry-After")));
  }

  @Test
  @DisplayName("Preflight libera o header de identidade X-Usuario-Id (#91)")
  void preflightLiberaHeaderDeIdentidade() throws Exception {
    // A CorsConfig aceita qualquer header de requisicao hoje, entao o X-Usuario-Id ja passa. Este
    // teste existe para que um aperto futuro dessa lista quebre aqui, e nao no navegador do
    // frontend: sem o header no preflight, o Angular perde a identidade sem erro de backend.
    mockMvc.perform(options("/api/notas")
            .header(ORIGIN, ORIGEM_ANGULAR)
            .header(ACCESS_CONTROL_REQUEST_METHOD, "GET")
            .header(ACCESS_CONTROL_REQUEST_HEADERS, MockIdentidadeFilter.HEADER_USUARIO_ID))
        .andExpect(status().isOk())
        .andExpect(header().string(ACCESS_CONTROL_ALLOW_ORIGIN, ORIGEM_ANGULAR))
        .andExpect(header().string(ACCESS_CONTROL_ALLOW_HEADERS,
            Matchers.containsString(MockIdentidadeFilter.HEADER_USUARIO_ID)));
  }

  @Test
  @DisplayName("Preflight de origem desconhecida e rejeitado")
  void preflightDeOrigemDesconhecidaRejeitado() throws Exception {
    mockMvc.perform(options("/api/notas")
            .header(ORIGIN, "http://malicioso.example")
            .header(ACCESS_CONTROL_REQUEST_METHOD, "GET"))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("Subdominio do escritorio: a SPA chama /api na mesma origem e nao e CORS (#464)")
  void subdominioNaMesmaOrigemNaoECors() throws Exception {
    // Como na VPS: HTTPS no Caddy, host do escritorio, Origin igual ao host. Sem CORS, o login
    // segue para a regra de sempre (401 de credencial errada), nunca o 403 do CORS.
    mockMvc.perform(post("/api/auth/login")
            .with(request -> {
              request.setScheme("https");
              request.setSecure(true);
              request.setServerPort(443);
              return request;
            })
            .header(HttpHeaders.HOST, "ruraloeste.agroconia.com.br")
            .header(ORIGIN, "https://ruraloeste.agroconia.com.br")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"ninguem@lugar.com\",\"senha\":\"x\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("Subdominio chamando outro host: e CORS e a origem nao esta na lista (403, #464)")
  void outroSubdominioRecusado() throws Exception {
    mockMvc.perform(post("/api/auth/login")
            .with(request -> {
              request.setScheme("https");
              request.setSecure(true);
              request.setServerPort(443);
              return request;
            })
            .header(HttpHeaders.HOST, "ruraloeste.agroconia.com.br")
            .header(ORIGIN, "https://outro.agroconia.com.br")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"ninguem@lugar.com\",\"senha\":\"x\"}"))
        .andExpect(status().isForbidden());
  }
}
