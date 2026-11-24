package br.com.agrocontabil.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.MockMvc;

/** Termos de uso pela cadeia real de segurança: público, como a política de dados (#498). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
@DisplayName("GET /api/termos-de-uso: texto público dos termos (#498)")
class TermosUsoIntegrationTest {

  @Autowired private MockMvc mockMvc;

  @Test
  @DisplayName("sem login: 200, JSON, versão do aceite, controlador, contato e as seções")
  void publico() throws Exception {
    mockMvc.perform(get("/api/termos-de-uso"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.versao").value("2026-11-24"))
        .andExpect(jsonPath("$.controlador").value("GWNext"))
        .andExpect(jsonPath("$.contato").value("contato@gwnext.com.br"))
        .andExpect(jsonPath("$.secoes.length()").value(10))
        .andExpect(jsonPath("$.secoes[0].id").value("quem-somos"))
        .andExpect(jsonPath("$.secoes[0].titulo").value("Quem oferece o AgroConIA"))
        .andExpect(jsonPath("$.secoes[0].paragrafos[0]").value(
            Matchers.containsString("AgroConIA")))
        .andExpect(jsonPath("$.secoes[1].itens.length()").value(Matchers.greaterThan(0)));
  }

  @Test
  @DisplayName("a versão é uma data AAAA-MM-DD, a mesma propriedade que o cadastro grava")
  void versaoEmFormatoDeData() throws Exception {
    mockMvc.perform(get("/api/termos-de-uso"))
        .andExpect(jsonPath("$.versao").value(Matchers.matchesPattern("\\d{4}-\\d{2}-\\d{2}")));
  }

  @Test
  @DisplayName("só leitura: POST não é aceito")
  void soLeitura() throws Exception {
    mockMvc.perform(post("/api/termos-de-uso"))
        .andExpect(status().is4xxClientError());
  }
}
