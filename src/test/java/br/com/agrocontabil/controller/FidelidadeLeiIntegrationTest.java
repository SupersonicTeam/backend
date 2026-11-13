package br.com.agrocontabil.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.agrocontabil.assistente.RotaLegislacao;
import br.com.agrocontabil.assistente.rag.BuscaLegislacaoService;
import br.com.agrocontabil.assistente.rag.TrechoRecuperado;
import br.com.agrocontabil.config.LimiteCorpoAssistenteFilter;
import br.com.agrocontabil.config.MockIdentidadeFilter;
import br.com.agrocontabil.model.DesfechoFidelidadeLei;
import br.com.agrocontabil.model.DesfechoInteracao;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.repository.AssistenteInteracaoRepository;
import br.com.agrocontabil.repository.ContadorRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.support.ChatModelSimulado;
import br.com.agrocontabil.support.TestDatabaseCleaner;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Conferência de fidelidade da rota de lei de ponta a ponta (#269), com a flag
 * {@code app.assistente.fidelidade-lei.enabled} ligada: o bean da rota recebe a conferência, a
 * resposta aprovada sai com a citação no caput, a reprovada sai só com os trechos, e o desfecho vai à
 * auditoria ({@code fidelidade_lei}, changeSet 0065), sem texto.
 */
@SpringBootTest(properties = "app.assistente.fidelidade-lei.enabled=true")
@Import(TestDatabaseCleaner.class)
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
class FidelidadeLeiIntegrationTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String URL = "https://www.planalto.gov.br/ccivil_03/leis/lcp/lcp214.htm";
  private static final String CAPUT = "Art. 138. Ficam reduzidas em 60% as aliquotas do IBS e da CBS"
      + " incidentes sobre os insumos agropecuarios.";
  private static final TrechoRecuperado PARAGRAFO = new TrechoRecuperado("T1", "id-par",
      "LC 214/2025", "art. 138, § 1º", URL,
      CAPUT + " § 1º O disposto neste artigo aplica-se aos fertilizantes e defensivos.", 0.9);
  private static final String COPIA = "Ficam reduzidas em 60% as aliquotas do IBS e da CBS "
      + "incidentes sobre os insumos agropecuarios";

  @Autowired private WebApplicationContext webApplicationContext;
  @Autowired private ProdutorRepository produtorRepository;
  @Autowired private ContadorRepository contadorRepository;
  @Autowired private ChatModelSimulado modelo;
  @Autowired private TestDatabaseCleaner cleaner;
  @Autowired private AssistenteInteracaoRepository interacaoRepository;
  @Autowired private LimiteCorpoAssistenteFilter limiteCorpoFilter;
  @MockitoBean private BuscaLegislacaoService busca;

  private MockMvc mockMvc;
  private Produtor produtor;

  @BeforeEach
  void setUp() {
    cleaner.limparTudo();
    modelo.reiniciar();
    modelo.classificarComo("LEGISLACAO");
    when(busca.buscar(anyString(), any())).thenReturn(List.of(PARAGRAFO));
    produtor = produtorRepository.save(Produtor.builder()
        .nomeCompleto("Fazenda Fidelidade").cpfCnpj("98765432100").estado("MT").build());
    mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
        .addFilters(limiteCorpoFilter,
            new MockIdentidadeFilter(produtorRepository, contadorRepository))
        .build();
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("aprovada: citação no caput, desfecho RESPONDIDA e APROVADA_CITACAO_CORRIGIDA")
  void aprovadaComCitacaoNoCaput() throws Exception {
    modelo.responderTexto("{\"frases\": [{\"frase\": \"Os insumos agropecuarios tem as aliquotas "
        + "reduzidas em 60%.\", \"evidencias\": [{\"trecho\": \"T1\", \"copia\": \"" + COPIA
        + "\"}]}]}");

    perguntar("Qual a reducao das aliquotas dos insumos agropecuarios na reforma?")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rota").value("LEGISLACAO"))
        .andExpect(jsonPath("$.resposta").value(org.hamcrest.Matchers.startsWith(
            "Os insumos agropecuarios tem as aliquotas reduzidas em 60% (LC 214/2025, art. 138).")))
        .andExpect(jsonPath("$.citacoes[0].dispositivo").value("art. 138"))
        .andExpect(jsonPath("$.citacoes.length()").value(1));
    // O prompt foi o da evidencia por frase.
    assertThat(modelo.promptsRecebidos()).singleElement()
        .satisfies(p -> assertThat(p.getSystemMessage().getText()).contains("\"copia\""));
    assertThat(interacaoRepository.findByProdutorIdOrderByCriadoEmDesc(produtor.getId()))
        .singleElement().satisfies(i -> {
          assertThat(i.getDesfecho()).isEqualTo(DesfechoInteracao.RESPONDIDA);
          assertThat(i.getFidelidadeLei())
              .isEqualTo(DesfechoFidelidadeLei.APROVADA_CITACAO_CORRIGIDA);
        });
  }

  @Test
  @DisplayName("reprovada: só os trechos marcados, desfecho FIDELIDADE_REPROVADA e o motivo")
  void reprovadaFicamOsTrechos() throws Exception {
    modelo.responderTexto("{\"frases\": [{\"frase\": \"Os insumos agropecuarios nao tem "
        + "reducao.\", \"evidencias\": [{\"trecho\": \"T1\", \"copia\": \"" + COPIA + "\"}]}]}");

    perguntar("Qual a reducao das aliquotas dos insumos agropecuarios na reforma?")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.resposta").value(RotaLegislacao.TRECHOS_SEM_CONFIRMACAO))
        .andExpect(jsonPath("$.citacoes[0].dispositivo").value("art. 138, § 1º"));
    assertThat(interacaoRepository.findByProdutorIdOrderByCriadoEmDesc(produtor.getId()))
        .singleElement().satisfies(i -> {
          assertThat(i.getDesfecho()).isEqualTo(DesfechoInteracao.FIDELIDADE_REPROVADA);
          assertThat(i.getFidelidadeLei()).isEqualTo(DesfechoFidelidadeLei.REPROVADA_POLARIDADE);
        });
  }

  private ResultActions perguntar(String pergunta) throws Exception {
    return mockMvc.perform(post("/api/assistente/chat")
        .header(MockIdentidadeFilter.HEADER_USUARIO_ID, produtor.getId())
        .contentType(MediaType.APPLICATION_JSON)
        .content(JSON.writeValueAsString(Map.of("pergunta", pergunta))));
  }
}
