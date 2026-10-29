package br.com.agrocontabil.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.support.AutenticacaoTestSupport;
import br.com.agrocontabil.support.TestDatabaseCleaner;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@link TributosLegadoRunner} (issue #272): a nota não emitida calculada antes da tabela oficial
 * é recalculada pela regra atual; a emitida não muda.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestDatabaseCleaner.class)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=none")
@Sql(
    scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
@DirtiesContext(classMode = ClassMode.AFTER_CLASS)
class TributosLegadoRunnerIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ProdutorRepository produtorRepository;
  @Autowired private TestDatabaseCleaner cleaner;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private TributosLegadoRunner runner;

  @BeforeEach
  void setUp() {
    cleaner.limparTudo();
    Produtor produtor = produtorRepository.save(Produtor.builder()
        .nomeCompleto("Joao Produtor").cpfCnpj("52998224725").estado("MT").build());
    SecurityContextHolder.getContext().setAuthentication(
        AutenticacaoTestSupport.tokenDe(produtor));
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
    cleaner.limparTudo();
  }

  @Test
  @DisplayName("rascunho do calculo antigo (arroz a 60%) volta a zero; nota emitida nao muda")
  void recalculaSoNaoEmitidas() throws Exception {
    String rascunho = criarNota("10063021");
    String emitida = criarNota("10063021");
    // Simula o calculo de antes do 0048: reducao hipotetica de 60% e sem o tratamento aplicado.
    for (String id : new String[] {rascunho, emitida}) {
      jdbc.update("UPDATE nota_fiscal_item SET percentual_reducao_aplicado = NULL,"
          + " c_class_trib_aplicado = NULL, base_calculo_ibs_cbs = 400.00, valor_ibs = 0.40"
          + " WHERE nota_fiscal_id = ?", java.util.UUID.fromString(id));
    }
    jdbc.update("UPDATE nota_fiscal SET status = 'AUTORIZADA' WHERE id = ?",
        java.util.UUID.fromString(emitida));

    assertThat(runner.recalcularLegado()).isEqualTo(1);

    assertThat(item(rascunho)).containsEntry("C_CLASS_TRIB_APLICADO", "200003");
    assertThat((BigDecimal) item(rascunho).get("BASE_CALCULO_IBS_CBS"))
        .isEqualByComparingTo("0.00");
    assertThat(item(emitida).get("C_CLASS_TRIB_APLICADO")).isNull();
    assertThat((BigDecimal) item(emitida).get("BASE_CALCULO_IBS_CBS"))
        .isEqualByComparingTo("400.00");
    // Segunda subida: nada a fazer.
    assertThat(runner.recalcularLegado()).isZero();
  }

  private Map<String, Object> item(String notaId) {
    return jdbc.queryForMap("SELECT c_class_trib_aplicado, base_calculo_ibs_cbs"
        + " FROM nota_fiscal_item WHERE nota_fiscal_id = ?", java.util.UUID.fromString(notaId));
  }

  private String criarNota(String ncm) throws Exception {
    String corpo = mockMvc.perform(post("/api/notas")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "numero": 1,
                  "naturezaOperacao": "Venda de producao",
                  "dataEmissao": "2026-06-10T10:00:00-03:00",
                  "destinatarioNome": "Comprador",
                  "destinatarioCpfCnpj": "52998224725",
                  "itens": [{"numeroItem": 1, "descricao": "Arroz", "ncm": "%s",
                    "cfop": "5101", "unidade": "KG", "quantidade": 100, "valorUnitario": 10}]
                }
                """.formatted(ncm)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.itens[0].percentualReducaoAplicado").value(100))
        .andExpect(jsonPath("$.itens[0].cClassTribAplicado").value("200003"))
        .andReturn().getResponse().getContentAsString();
    return JsonPath.read(corpo, "$.id");
  }
}
