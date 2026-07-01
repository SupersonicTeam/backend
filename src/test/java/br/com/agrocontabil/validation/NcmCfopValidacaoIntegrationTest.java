package br.com.agrocontabil.validation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Testes de integração da validação de NCM/CFOP (issue #20, RN008).
 *
 * <p>Sobe o contexto Spring completo com o seed de referência carregado via {@code @Sql}
 * (necessário porque o perfil de teste desabilita o Liquibase). Exercita:
 * <ul>
 *   <li>{@code POST /api/notas} com NCM inexistente → 400;</li>
 *   <li>{@code POST /api/notas} com CFOP inaplicável ao produtor rural → 400;</li>
 *   <li>{@code POST /api/notas} com NCM e CFOP válidos → 201.</li>
 * </ul>
 *
 * <p>A regra de compatibilidade CFOP × âmbito (422) é coberta no nível do serviço por
 * {@code FiscalRulesValidatorCfopAmbitoTest} e pelo fluxo de geração de XML existente.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=none")
@Sql(
    scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
@DirtiesContext(classMode = ClassMode.AFTER_CLASS)
class NcmCfopValidacaoIntegrationTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ProdutorRepository produtorRepository;

  @Autowired
  private NotaFiscalRepository notaFiscalRepository;

  private Produtor produtor;

  @BeforeEach
  void setUp() {
    produtor = produtorRepository.save(Produtor.builder()
        .nomeCompleto("Maria Agricultora")
        .cpfCnpj("98765432100")
        .estado("PR")
        .build());

    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken(produtor, null, List.of()));
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
    notaFiscalRepository.deleteAll();
    produtorRepository.deleteAll();
  }

  @Test
  @DisplayName("POST com NCM inexistente na tabela de referencia deve retornar 400")
  void postComNcmInexistenteDeveRetornar400() throws Exception {
    String payload = payload("99999999", "6101");

    mockMvc.perform(post("/api/notas")
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[?(@.field == 'itens[0].ncm')]").exists())
        .andExpect(jsonPath("$.errors[?(@.code == 'VALIDATION_ERROR')]").exists());
  }

  @Test
  @DisplayName("POST com CFOP inaplicavel ao produtor rural deve retornar 400")
  void postComCfopInaplicavelDeveRetornar400() throws Exception {
    // 1556 existe na tabela mas nao e aplicavel ao produtor rural.
    String payload = payload("12010090", "1556");

    mockMvc.perform(post("/api/notas")
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[?(@.field == 'itens[0].cfop')]").exists())
        .andExpect(jsonPath("$.errors[?(@.code == 'VALIDATION_ERROR')]").exists());
  }

  @Test
  @DisplayName("POST com NCM e CFOP validos deve retornar 201")
  void postComNcmCfopValidosDeveRetornar201() throws Exception {
    String payload = payload("12010090", "6101");

    mockMvc.perform(post("/api/notas")
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isCreated());
  }

  private String payload(String ncm, String cfop) {
    return """
        {
          "numero": 1,
          "naturezaOperacao": "Venda de producao",
          "dataEmissao": "2026-06-10T10:00:00-03:00",
          "destinatarioNome": "Comprador",
          "destinatarioCpfCnpj": "52998224725",
          "itens": [
            {
              "numeroItem": 1,
              "descricao": "Soja em graos",
              "ncm": "%s",
              "cfop": "%s",
              "unidade": "KG",
              "quantidade": 100,
              "valorUnitario": 2.50
            }
          ]
        }
        """.formatted(ncm, cfop);
  }
}
