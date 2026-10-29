package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.support.TestDatabaseCleaner;
import br.com.agrocontabil.support.AutenticacaoTestSupport;
import java.math.BigDecimal;
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
 * Teste de integração da vigência temporal das alíquotas (Onda 2, issue #85; divisão do IBS por
 * esfera, issue #277).
 *
 * <p>Sobe o contexto completo com o seed de referência (schema H2 espelho dos changesets
 * {@code 0013}/{@code 0020}/{@code 0047}) e exercita o fluxo real {@code POST /api/notas}:
 * <ul>
 *   <li>nota emitida em 2026 → IBS de 0,1% todo estadual ({@code 250,00 × 0,0010 = 0,25} no
 *       {@code vIBSUF}, zero no {@code vIBSMun}) — EC 132/2023, NT 2026.010 item 2;</li>
 *   <li>nota emitida em 2027 → mesma alíquota total, dividida 0,05% + 0,05% (LC 214/2025,
 *       art. 344): {@code 0,13} estadual (arredondado) e {@code 0,12} municipal, que fecha o
 *       total.</li>
 * </ul>
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
class AliquotaVigenciaIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ProdutorRepository produtorRepository;
  @Autowired private TestDatabaseCleaner cleaner;
  @Autowired private JdbcTemplate jdbc;

  private Produtor produtor;

  @BeforeEach
  void setUp() {
    // O H2 e compartilhado entre contextos: outra classe pode ter deixado guias/apuracoes do
    // proprio produtor dela, e o tearDown so consegue apagar produtores na ordem das FKs.
    cleaner.limparTudo();
    produtor = produtorRepository.save(Produtor.builder()
        .nomeCompleto("Joao Produtor")
        .cpfCnpj("52998224725")
        .estado("MT")
        .build());
    SecurityContextHolder.getContext().setAuthentication(
        AutenticacaoTestSupport.tokenDe(produtor));
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
    cleaner.limparTudo();
  }

  @Test
  @DisplayName("nota emitida em 2026: IBS de 0,1% todo estadual, municipal zero (#277)")
  void notaDe2026TemIbsSoEstadual() throws Exception {
    mockMvc.perform(post("/api/notas")
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload("2026-12-31T23:00:00-03:00")))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.itens[0].aliquotaIbs").value(0.0010))
        .andExpect(jsonPath("$.valorTotalIbs").value(0.25));

    assertThat(parcelas()).containsExactly(
        new BigDecimal("0.0010"), new BigDecimal("0.0000"),
        new BigDecimal("0.25"), new BigDecimal("0.00"));
  }

  @Test
  @DisplayName("nota emitida em 2027: mesmo IBS total, dividido 0,05% + 0,05% (art. 344, #277)")
  void notaDe2027TemIbsDividido() throws Exception {
    mockMvc.perform(post("/api/notas")
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload("2027-01-01T00:00:00-03:00")))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.itens[0].aliquotaIbs").value(0.0010))
        .andExpect(jsonPath("$.valorTotalIbs").value(0.25));

    assertThat(parcelas()).containsExactly(
        new BigDecimal("0.0005"), new BigDecimal("0.0005"),
        new BigDecimal("0.13"), new BigDecimal("0.12"));
  }

  @Test
  @DisplayName("vigencias sobrepostas: a de inicio mais recente vence (ORDER BY data_inicio DESC)")
  void sobreposicaoVenceAMaisRecente() throws Exception {
    // Vigencia aberta de MT a partir de 2027-07-01 por cima da de 2027-2028 do seed.
    jdbc.update("INSERT INTO aliquota_ibs_uf (uf, data_inicio, data_fim, aliquota_estadual,"
        + " aliquota_municipal) VALUES ('MT', DATE '2027-07-01', NULL, 0.0010, 0.0010)");
    try {
      mockMvc.perform(post("/api/notas")
              .contentType(MediaType.APPLICATION_JSON)
              .content(payload("2027-08-10T10:00:00-03:00")))
          .andExpect(status().isCreated())
          .andExpect(jsonPath("$.itens[0].aliquotaIbs").value(0.0020))
          .andExpect(jsonPath("$.valorTotalIbs").value(0.50));
    } finally {
      // O H2 sobrevive ao contexto (DB_CLOSE_DELAY=-1): a linha nao pode ficar para outra classe.
      jdbc.update("DELETE FROM aliquota_ibs_uf WHERE uf = 'MT'"
          + " AND data_inicio = DATE '2027-07-01'");
    }
  }

  /** Alíquota e valor estadual e municipal do único item gravado. */
  private List<BigDecimal> parcelas() {
    return jdbc.queryForObject("SELECT aliquota_ibs_uf, aliquota_ibs_mun, valor_ibs_uf,"
        + " valor_ibs_mun FROM nota_fiscal_item", (rs, i) -> List.of(
            rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getBigDecimal(3), rs.getBigDecimal(4)));
  }

  private static String payload(String dataEmissao) {
    // 100 KG × 2,50 = 250,00; NCM 12019000 (soja) sem redução padrão no seed.
    return """
        {
          "numero": 1,
          "naturezaOperacao": "Venda de producao",
          "dataEmissao": "%s",
          "destinatarioNome": "Comprador",
          "destinatarioCpfCnpj": "52998224725",
          "itens": [
            {
              "numeroItem": 1,
              "descricao": "Soja em graos",
              "ncm": "12019000",
              "cfop": "6101",
              "unidade": "KG",
              "quantidade": 100,
              "valorUnitario": 2.50
            }
          ]
        }
        """.formatted(dataEmissao);
  }
}
