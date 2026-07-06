package br.com.agrocontabil.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Contrato de erro da API para consumo do frontend Angular (issue Agrocontabil#66 — Sprint 5).
 *
 * <p>Garante que erros de infraestrutura HTTP — que nao passam pelas exceções de domínio —
 * também respondem o envelope {@code ApiError.errors[]} com o status correto, em vez de
 * caírem na rede de segurança genérica (500):
 *
 * <ul>
 *   <li>corpo JSON malformado → 400;</li>
 *   <li>path variable com tipo inválido (UUID) → 400;</li>
 *   <li>rota inexistente → 404;</li>
 *   <li>método HTTP não suportado → 405;</li>
 *   <li>Content-Type não suportado → 415.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiErrorContractIntegrationTest {

  @Autowired
  private MockMvc mockMvc;

  @Test
  @DisplayName("POST com JSON malformado responde 400 com envelope errors[]")
  void jsonMalformadoResponde400ComEnvelope() throws Exception {
    mockMvc.perform(post("/api/notas")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"naturezaOperacao\": \"Venda\", "))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.path").value("/api/notas"))
        .andExpect(jsonPath("$.errors[0].code").value("VALIDATION_ERROR"));
  }

  @Test
  @DisplayName("GET com id que nao e UUID responde 400 com envelope errors[]")
  void pathVariableComTipoInvalidoResponde400ComEnvelope() throws Exception {
    mockMvc.perform(get("/api/notas/nao-e-uuid"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.errors[0].code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.errors[0].field").value("id"))
        .andExpect(jsonPath("$.errors[0].rejectedValue").value("nao-e-uuid"));
  }

  @Test
  @DisplayName("Rota inexistente responde 404 com envelope errors[]")
  void rotaInexistenteResponde404ComEnvelope() throws Exception {
    mockMvc.perform(get("/api/rota-que-nao-existe"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.errors[0].code").value("RESOURCE_NOT_FOUND"));
  }

  @Test
  @DisplayName("Metodo HTTP nao suportado responde 405 com envelope errors[]")
  void metodoNaoSuportadoResponde405ComEnvelope() throws Exception {
    mockMvc.perform(patch("/api/notas"))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(jsonPath("$.status").value(405))
        .andExpect(jsonPath("$.errors[0].code").value("METHOD_NOT_ALLOWED"));
  }

  @Test
  @DisplayName("Content-Type nao suportado responde 415 com envelope errors[]")
  void contentTypeNaoSuportadoResponde415ComEnvelope() throws Exception {
    mockMvc.perform(post("/api/notas")
            .contentType(MediaType.TEXT_PLAIN)
            .content("nao sou json"))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(jsonPath("$.status").value(415))
        .andExpect(jsonPath("$.errors[0].code").value("MEDIA_TYPE_NOT_SUPPORTED"));
  }
}
