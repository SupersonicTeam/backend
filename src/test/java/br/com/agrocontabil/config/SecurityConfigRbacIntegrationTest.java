package br.com.agrocontabil.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Prova as regras da cadeia de filtros depois do fim do {@code permitAll()} — issue #93.
 *
 * <p>O ponto crítico não é só "barrou": é <strong>como</strong> barrou. Negativa de filtro acontece
 * antes do controller e não passa pelo {@code @RestControllerAdvice}; sem o
 * {@code SecurityErrorResponder} o cliente receberia a página de erro do container, quebrando o
 * contrato {@code ApiError.errors[]} que a issue #66 fechou com o Angular.
 *
 * <p>O profile é {@code test}, onde o {@code MockIdentidadeFilter} não é registrado — ou seja, é o
 * mesmo cenário de produção antes do login real: requisição sem identidade.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("SecurityConfig — regras por papel e envelope de erro (#93)")
class SecurityConfigRbacIntegrationTest {

  @Autowired private MockMvc mockMvc;

  @Test
  @DisplayName("health continua publico, sem identidade")
  void healthEhPublico() throws Exception {
    mockMvc.perform(get("/api/v1/health")).andExpect(status().isOk());
  }

  @Test
  @DisplayName("documentacao OpenAPI continua publica")
  void openApiEhPublico() throws Exception {
    mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
  }

  @Test
  @DisplayName("endpoint de negocio sem identidade responde 401 com envelope e WWW-Authenticate")
  void semIdentidadeResponde401ComEnvelope() throws Exception {
    mockMvc
        .perform(get("/api/dashboard"))
        .andExpect(status().isUnauthorized())
        // RFC 9110 §15.5.2 / RFC 6750 §3 — desde a #206 o desafio e Bearer.
        .andExpect(header().string("WWW-Authenticate",
            Matchers.startsWith("Bearer ")))
        .andExpect(jsonPath("$.status").value(401))
        .andExpect(jsonPath("$.path").value("/api/dashboard"))
        .andExpect(jsonPath("$.errors[0].code").value("NAO_AUTENTICADO"))
        .andExpect(jsonPath("$.errors[0].suggestion").value(
            Matchers.containsString("/api/auth/login")));
  }

  @Test
  @DisplayName("listagem de guias sem identidade responde 401, nao 404 nem pagina do container")
  void guiasSemIdentidadeResponde401() throws Exception {
    mockMvc
        .perform(get("/api/contador/guias").param("produtorId", UUID.randomUUID().toString()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.errors[0].code").value("NAO_AUTENTICADO"));
  }

  @Test
  @DisplayName("contador barrado no pagamento pela cadeia responde 403 com envelope")
  @WithMockUser(roles = "CONTADOR")
  void contadorNaoPagaGuia() throws Exception {
    mockMvc
        .perform(patch("/api/contador/guias/{id}/pagar", UUID.randomUUID()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.status").value(403))
        .andExpect(jsonPath("$.errors[0].code").value("PAPEL_NAO_AUTORIZADO"))
        // Nao diz se a guia existe: quem decide o papel nao consulta o recurso.
        .andExpect(jsonPath("$.errors[0].rejectedValue").doesNotExist());
  }

  @Test
  @DisplayName("produtor passa pela cadeia no pagamento (a decisao seguinte e do servico)")
  @WithMockUser(roles = "PRODUTOR")
  void produtorPassaNaCadeiaAoPagar() throws Exception {
    // Sem identidade real por tras do @WithMockUser, o servico responde 401 — o que importa aqui e
    // que a requisicao NAO parou no 403 da cadeia.
    mockMvc
        .perform(patch("/api/contador/guias/{id}/pagar", UUID.randomUUID()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("papel desconhecido nao alcanca endpoint de negocio")
  @WithMockUser(roles = "VISITANTE")
  void papelDesconhecidoRecebe403() throws Exception {
    mockMvc
        .perform(get("/api/dashboard"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errors[0].code").value("PAPEL_NAO_AUTORIZADO"));
  }
}
