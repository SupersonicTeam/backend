package br.com.agrocontabil.service;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.agrocontabil.model.AliquotaIbsUf;
import br.com.agrocontabil.model.AliquotaIbsUfId;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.repository.AliquotaIbsUfRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.support.TestDatabaseCleaner;
import java.math.BigDecimal;
import java.time.LocalDate;
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
 * Teste de integração da vigência temporal das alíquotas (Onda 2, issue #85 — FISC-04/FISC-07).
 *
 * <p>Sobe o contexto completo com o seed de referência (schema H2 espelho dos changesets
 * {@code 0013}/{@code 0019}) e insere uma segunda vigência para MT a partir de 2027-01-01
 * ({@code 0,0010 + 0,0010 = 0,0020}). Exercita o fluxo real {@code POST /api/notas}:
 * <ul>
 *   <li>nota emitida em 2027 → IBS pela segunda vigência ({@code 250,00 × 0,0020 = 0,50});</li>
 *   <li>nota emitida em 2026 → IBS pela vigência do seed ({@code 250,00 × 0,0010 = 0,25}),
 *       inalterada pela inserção.</li>
 * </ul>
 *
 * <p>A vigência de 2026 do seed é aberta ({@code data_fim = NULL}) e também cobre 2027 — a query
 * ordenada por {@code data_inicio DESC} garante que a de início mais recente vence (edge case de
 * sobreposição do spec). A vigência inserida é removida no {@code @AfterEach} para não contaminar
 * outros testes que compartilham o seed.
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

  private static final LocalDate INICIO_2027 = LocalDate.of(2027, 1, 1);

  @Autowired private MockMvc mockMvc;
  @Autowired private ProdutorRepository produtorRepository;
  @Autowired private TestDatabaseCleaner cleaner;
  @Autowired private AliquotaIbsUfRepository aliquotaIbsUfRepository;

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
        new UsernamePasswordAuthenticationToken(produtor, null, List.of()));

    // Segunda vigência de MT: 2027 em diante, aliquota dobrada (0,0010 + 0,0010).
    aliquotaIbsUfRepository.save(AliquotaIbsUf.builder()
        .uf("MT")
        .dataInicio(INICIO_2027)
        .dataFim(null)
        .aliquotaEstadual(new BigDecimal("0.0010"))
        .aliquotaMunicipal(new BigDecimal("0.0010"))
        .build());
  }

  @AfterEach
  void tearDown() {
    // A vigencia sai primeiro: o H2 em memoria sobrevive ao @DirtiesContext
    // (DB_CLOSE_DELAY=-1), entao uma falha nos deleteAll nao pode deixa-la para tras.
    aliquotaIbsUfRepository.deleteById(new AliquotaIbsUfId("MT", INICIO_2027));
    SecurityContextHolder.clearContext();
    cleaner.limparTudo();
  }

  @Test
  @DisplayName("nota emitida em 2027 usa a segunda vigencia de MT (0,0020)")
  void notaDe2027UsaSegundaVigencia() throws Exception {
    mockMvc.perform(post("/api/notas")
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload("2027-03-15T10:00:00-03:00")))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.itens[0].aliquotaIbs").value(0.0020))
        .andExpect(jsonPath("$.valorTotalIbs").value(0.50));
  }

  @Test
  @DisplayName("nota emitida em 2026 continua na vigencia do seed (0,0010)")
  void notaDe2026UsaVigenciaDoSeed() throws Exception {
    mockMvc.perform(post("/api/notas")
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload("2026-06-10T10:00:00-03:00")))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.itens[0].aliquotaIbs").value(0.0010))
        .andExpect(jsonPath("$.valorTotalIbs").value(0.25));
  }

  private static String payload(String dataEmissao) {
    // 100 KG × 2,50 = 250,00; NCM 12010090 (soja) sem redução padrão no seed.
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
              "ncm": "12010090",
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
