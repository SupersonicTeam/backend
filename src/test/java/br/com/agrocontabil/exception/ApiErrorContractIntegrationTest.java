package br.com.agrocontabil.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.agrocontabil.service.NotaFiscalService;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.OptimisticLockingFailureException;
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
 *   <li>Content-Type não suportado → 415;</li>
 *   <li>conflito de lock otimista → 409 (Onda 3, issue #95).</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiErrorContractIntegrationTest {

  @Autowired
  private MockMvc mockMvc;

  /**
   * O conflito de versão nasce de duas transações reais disputando a mesma linha, cenário que o
   * MockMvc (uma requisição por vez) não reproduz — o {@code NotaFiscalConcorrenciaIntegrationTest}
   * cobre esse lado. Aqui interessa o outro lado: que a exceção, vindo de onde vier, chegue ao
   * cliente como 409 com o envelope certo. Por isso o serviço é dublado.
   */
  @MockBean
  private NotaFiscalService notaFiscalService;

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
        .andExpect(jsonPath("$.errors[0].code").value("METHOD_NOT_ALLOWED"))
        // RFC 7231 §6.5.5: o 405 deve manter o header Allow com os metodos suportados.
        .andExpect(header().string("Allow", Matchers.containsString("POST")));
  }

  @Test
  @DisplayName("Content-Type nao suportado responde 415 com envelope errors[]")
  void contentTypeNaoSuportadoResponde415ComEnvelope() throws Exception {
    mockMvc.perform(post("/api/notas")
            .contentType(MediaType.TEXT_PLAIN)
            .content("nao sou json"))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(jsonPath("$.status").value(415))
        .andExpect(jsonPath("$.errors[0].code").value("MEDIA_TYPE_NOT_SUPPORTED"))
        // RFC 7231 §6.5.13: o 415 deve manter o header Accept com os media types aceitos.
        .andExpect(header().string("Accept", Matchers.containsString(MediaType.APPLICATION_JSON_VALUE)));
  }

  @Test
  @DisplayName("Conflito de lock otimista responde 409 com envelope errors[] e sem detalhe tecnico")
  void conflitoDeLockOtimistaResponde409ComEnvelope() throws Exception {
    UUID notaId = UUID.randomUUID();
    org.mockito.BDDMockito.given(notaFiscalService.gerarXml(notaId))
        .willThrow(new OptimisticLockingFailureException(
            "Row was updated or deleted by another transaction "
                + "(or unsaved-value mapping was incorrect): "
                + "[br.com.agrocontabil.model.NotaFiscal#" + notaId + "]"));

    mockMvc.perform(post("/api/notas/{id}/gerar-xml", notaId))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.status").value(409))
        .andExpect(jsonPath("$.path").value("/api/notas/" + notaId + "/gerar-xml"))
        .andExpect(jsonPath("$.errors[0].code").value("CONFLITO_CONCORRENCIA"))
        // A mensagem orienta a acao do cliente e a suggestion vem preenchida (o Angular a exibe).
        .andExpect(jsonPath("$.errors[0].message").value(Matchers.containsString("recarregue")))
        .andExpect(jsonPath("$.errors[0].suggestion").value(Matchers.not(Matchers.emptyString())))
        // Nada do texto do ORM vaza no corpo: nem entidade, nem id, nem jargao de transacao.
        .andExpect(jsonPath("$.errors[0].message").value(Matchers.not(
            Matchers.containsString("NotaFiscal"))))
        .andExpect(jsonPath("$.message").value(Matchers.not(
            Matchers.containsString("unsaved-value"))));
  }
}
