package br.com.agrocontabil.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.agrocontabil.repository.CredencialUsuarioRepository;
import br.com.agrocontabil.support.TestDatabaseCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Cadastro com o aceite dos termos exigido ({@code app.cadastro.exigir-aceite-termos}, issue
 * #519): a flag que vira {@code true} quando a tela nova estiver no ar, sem mudar código.
 */
@SpringBootTest(properties = "app.cadastro.exigir-aceite-termos=true")
@AutoConfigureMockMvc
@Import(TestDatabaseCleaner.class)
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
@DisplayName("POST /api/auth/cadastro com o aceite dos termos exigido (#519)")
class CadastroAceiteObrigatorioIntegrationTest {

  private static final String CORPO = """
      {"papel":"PRODUTOR","nomeCompleto":"Fazenda Boa Vista","cpfCnpj":"52998224725",\
      "email":"aceite@fazenda.com.br","senha":"cavalo bateria grampo correto"%s}""";

  @Autowired private MockMvc mockMvc;
  @Autowired private CredencialUsuarioRepository credencialRepository;
  @Autowired private TestDatabaseCleaner cleaner;

  @BeforeEach
  void setUp() {
    cleaner.limparTudo();
  }

  @Test
  @DisplayName("sem o campo: 400 VALIDATION_ERROR no aceiteTermos, nada gravado")
  void semAceiteRecusado() throws Exception {
    mockMvc.perform(post("/api/auth/cadastro").contentType(MediaType.APPLICATION_JSON)
            .content(CORPO.formatted("")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.length()").value(1))
        .andExpect(jsonPath("$.errors[0].code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.errors[0].field").value("aceiteTermos"));
    assertThat(credencialRepository.count()).isZero();
  }

  @Test
  @DisplayName("com o aceite: 201 e o aceite gravado")
  void comAceite() throws Exception {
    mockMvc.perform(post("/api/auth/cadastro").contentType(MediaType.APPLICATION_JSON)
            .content(CORPO.formatted(",\"aceiteTermos\":true")))
        .andExpect(status().isCreated());
    assertThat(credencialRepository.findByEmail("aceite@fazenda.com.br").orElseThrow()
        .getTermosAceitosEm()).isNotNull();
  }
}
