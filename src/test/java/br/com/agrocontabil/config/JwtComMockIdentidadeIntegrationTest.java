package br.com.agrocontabil.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.support.IdentidadeMockadaConfig;
import br.com.agrocontabil.support.TestDatabaseCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.MockMvc;

/**
 * JWT e mock de identidade juntos, como em dev/homolog (issue #206).
 *
 * <p>O risco que este teste fecha: o {@code MockIdentidadeFilter} cai no produtor semente quando
 * a requisição não declara identidade. Se um {@code Authorization} inválido seguisse a cadeia sem
 * identidade, o mock o transformaria num acesso válido como o semente — um token adulterado
 * autenticaria.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({IdentidadeMockadaConfig.class, TestDatabaseCleaner.class})
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
@DisplayName("JWT antes do mock: Authorization invalido nunca vira o produtor semente (#206)")
class JwtComMockIdentidadeIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ProdutorRepository produtorRepository;
  @Autowired private TestDatabaseCleaner cleaner;

  private Produtor semente;

  @BeforeEach
  void setUp() {
    cleaner.limparTudo();
    semente = produtorRepository.save(Produtor.builder()
        .nomeCompleto("Produtor Semente").cpfCnpj(DataLoader.CPF_PRODUTOR_SEMENTE).build());
  }

  @Test
  @DisplayName("sem Authorization: o mock segue resolvendo o semente (Angular atual nao quebra)")
  void semAuthorizationCaiNoSemente() throws Exception {
    mockMvc.perform(get("/api/auth/me"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.usuarioId").value(semente.getId().toString()));
  }

  @ParameterizedTest
  @ValueSource(strings = {"Bearer token.adulterado.aqui", "Bearer ", "Bearer", "Basic dXNlcjpwdw=="})
  @DisplayName("Authorization invalido, vazio ou de outro esquema: 401, nunca o semente")
  void authorizationInvalidoNaoCaiNoSemente(String autorizacao) throws Exception {
    mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, autorizacao))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.errors[0].code").value("NAO_AUTENTICADO"));
  }

  @Test
  @DisplayName("Bearer invalido junto de X-Usuario-Id valido: o token decide, 401")
  void bearerInvalidoVenceOHeaderMock() throws Exception {
    mockMvc.perform(get("/api/auth/me")
            .header(HttpHeaders.AUTHORIZATION, "Bearer token.adulterado.aqui")
            .header(MockIdentidadeFilter.HEADER_USUARIO_ID, semente.getId().toString()))
        .andExpect(status().isUnauthorized());
  }
}
