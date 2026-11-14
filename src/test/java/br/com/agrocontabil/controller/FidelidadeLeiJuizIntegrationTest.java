package br.com.agrocontabil.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.agrocontabil.assistente.PromptsCeres;
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
 * O juiz da fidelidade da lei de ponta a ponta (#269, camada 2), com a camada 1 e o juiz ligados: a
 * segunda chamada acontece, o veredito decide, e a auditoria grava o tempo e os tokens do juiz em
 * colunas próprias, fora dos da pergunta (changeSet 0065).
 */
@SpringBootTest(properties = {"app.assistente.fidelidade-lei.enabled=true",
    "app.assistente.fidelidade-lei.juiz.enabled=true"})
@Import(TestDatabaseCleaner.class)
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
class FidelidadeLeiJuizIntegrationTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String URL = "https://www.planalto.gov.br/ccivil_03/leis/lcp/lcp214.htm";
  private static final TrechoRecuperado INSUMOS = new TrechoRecuperado("T1", "id-1",
      "LC 214/2025", "art. 138", URL, "Art. 138. Ficam reduzidas em 60% as aliquotas do IBS e da "
      + "CBS incidentes sobre os insumos agropecuarios.", 0.9);
  private static final String RESPOSTA = "{\"frases\": [{\"frase\": \"Os insumos agropecuarios "
      + "tem as aliquotas reduzidas em 60%.\", \"evidencias\": [{\"trecho\": \"T1\", \"copia\": "
      + "\"Ficam reduzidas em 60% as aliquotas do IBS e da CBS incidentes sobre os insumos "
      + "agropecuarios\"}]}]}";

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
    when(busca.buscar(anyString(), any())).thenReturn(List.of(INSUMOS));
    produtor = produtorRepository.save(Produtor.builder()
        .nomeCompleto("Fazenda Juiz").cpfCnpj("98765432100").estado("MT").build());
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
  @DisplayName("juiz SUSTENTA: resposta entregue, colunas do juiz preenchidas")
  void juizAprova() throws Exception {
    modelo.responderEmSequencia(ChatModelSimulado.texto(RESPOSTA),
        ChatModelSimulado.texto("{\"vereditos\": [\"SUSTENTA\"]}"));

    perguntar()
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.resposta").value(org.hamcrest.Matchers.startsWith(
            "Os insumos agropecuarios tem as aliquotas reduzidas em 60% (LC 214/2025, art. 138).")));
    assertThat(modelo.promptsRecebidos()).hasSize(2);
    assertThat(modelo.promptsRecebidos().get(1).getSystemMessage().getText())
        .isEqualTo(PromptsCeres.JUIZ_FIDELIDADE);
    assertThat(interacaoRepository.findByProdutorIdOrderByCriadoEmDesc(produtor.getId()))
        .singleElement().satisfies(i -> {
          assertThat(i.getDesfecho()).isEqualTo(DesfechoInteracao.RESPONDIDA);
          assertThat(i.getFidelidadeLei()).isEqualTo(DesfechoFidelidadeLei.APROVADA);
          assertThat(i.getLatenciaJuizMs()).isNotNull();
          assertThat(i.getTokensEntradaJuiz()).isPositive();
          assertThat(i.getTokensSaidaJuiz()).isNotNull();
          // Os tokens da pergunta sao sem o juiz: os da resposta de lei sao os unicos que sobram.
          assertThat(i.getTokensEntrada()).isPositive();
        });
  }

  @Test
  @DisplayName("juiz CONTRADIZ: só os trechos, desfecho FIDELIDADE_REPROVADA e o motivo do juiz")
  void juizReprova() throws Exception {
    modelo.responderEmSequencia(ChatModelSimulado.texto(RESPOSTA),
        ChatModelSimulado.texto("{\"vereditos\": [\"CONTRADIZ\"]}"));

    perguntar()
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.resposta").value(RotaLegislacao.TRECHOS_SEM_CONFIRMACAO))
        .andExpect(jsonPath("$.citacoes[0].dispositivo").value("art. 138"));
    assertThat(interacaoRepository.findByProdutorIdOrderByCriadoEmDesc(produtor.getId()))
        .singleElement().satisfies(i -> {
          assertThat(i.getDesfecho()).isEqualTo(DesfechoInteracao.FIDELIDADE_REPROVADA);
          assertThat(i.getFidelidadeLei())
              .isEqualTo(DesfechoFidelidadeLei.REPROVADA_JUIZ_CONTRADIZ);
          assertThat(i.getTokensEntradaJuiz()).isPositive();
        });
  }

  private ResultActions perguntar() throws Exception {
    return mockMvc.perform(post("/api/assistente/chat")
        .header(MockIdentidadeFilter.HEADER_USUARIO_ID, produtor.getId())
        .contentType(MediaType.APPLICATION_JSON)
        .content(JSON.writeValueAsString(Map.of("pergunta",
            "Qual a reducao das aliquotas dos insumos agropecuarios na reforma?"))));
  }
}
