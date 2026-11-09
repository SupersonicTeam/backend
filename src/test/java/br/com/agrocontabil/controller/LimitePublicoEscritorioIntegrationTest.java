package br.com.agrocontabil.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Limite próprio do endpoint público do escritório (issue #464): acima dele, 429 com
 * {@code Retry-After}, mesmo para slug que não existe (a varredura é o que se quer frear).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "app.escritorio.publico.por-minuto=2")
@DisplayName("Endpoint publico do escritorio: limite por IP contra varredura (#464)")
class LimitePublicoEscritorioIntegrationTest {

  @Autowired private MockMvc mockMvc;

  @Test
  @DisplayName("a terceira consulta no minuto recebe 429 MUITAS_TENTATIVAS com Retry-After")
  void limitePorIp() throws Exception {
    mockMvc.perform(get("/api/publico/escritorios/varredura1")).andExpect(status().isNotFound());
    mockMvc.perform(get("/api/publico/escritorios/varredura2")).andExpect(status().isNotFound());
    mockMvc.perform(get("/api/publico/escritorios/varredura3"))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
        .andExpect(jsonPath("$.errors[0].code").value("MUITAS_TENTATIVAS"));
    // O ask fica fora: e outra rota, so da rede interna.
    mockMvc.perform(get("/interno/tls/permitido")).andExpect(status().isNotFound());
  }
}
