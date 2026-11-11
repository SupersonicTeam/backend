package br.com.agrocontabil.controller;

import static br.com.agrocontabil.support.DadosCeresFixture.em;
import static br.com.agrocontabil.support.DadosCeresFixture.entrada;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.agrocontabil.assistente.continuidade.Autocorrecao;
import br.com.agrocontabil.assistente.orquestracao.ConversaLeve;
import br.com.agrocontabil.config.LimiteCorpoAssistenteFilter;
import br.com.agrocontabil.config.MockIdentidadeFilter;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.repository.ContadorRepository;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.support.ChatModelSimulado;
import br.com.agrocontabil.support.TestDatabaseCleaner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Continuidade da conversa (#499) de ponta a ponta, com a flag ligada e o modelo simulado: o
 * turno assinado volta no histórico e a confirmação de dados reexecuta sem modelo nem crédito.
 */
@SpringBootTest
@Import(TestDatabaseCleaner.class)
@ActiveProfiles("test")
@TestPropertySource(properties = "app.assistente.continuidade.habilitada=true")
@Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
class ContinuidadeChatIntegrationTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String PERGUNTA =
      "Quanto comprei de fertilizante no primeiro semestre de 2026?";

  @Autowired private WebApplicationContext webApplicationContext;
  @Autowired private ProdutorRepository produtorRepository;
  @Autowired private ContadorRepository contadorRepository;
  @Autowired private NotaFiscalRepository notaFiscalRepository;
  @Autowired private ChatModelSimulado modelo;
  @Autowired private TestDatabaseCleaner cleaner;
  @Autowired private LimiteCorpoAssistenteFilter limiteCorpoFilter;

  private MockMvc mockMvc;
  private Produtor produtor;

  @BeforeEach
  void setUp() {
    cleaner.limparTudo();
    modelo.reiniciar();
    modelo.classificarComo("DADOS");
    produtor = produtorRepository.save(Produtor.builder()
        .nomeCompleto("Fazenda A").cpfCnpj("98765432100").estado("MT").build());
    notaFiscalRepository.save(entrada(produtor, StatusNotaFiscal.AUTORIZADA, em(2026, 2, 10),
        "FERTILIZANTE NPK 04-14-08", "31052000", "KG", "1000", "2500.00"));
    modelo.responderCom(prompt -> ChatModelSimulado.funcao("totalComprasPorInsumo", Map.of(
        "descricaoOuNcm", "fertilizante", "dataInicio", "2026-01-01",
        "dataFim", "2026-06-30")));
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
  @DisplayName("Dados e 'tem certeza?': reexecução sem modelo, 'o resultado é o mesmo'")
  void confirmacaoReexecutaSemModelo() throws Exception {
    JsonNode primeira = perguntar(PERGUNTA, List.of(), false);
    JsonNode turno = primeira.get("turno");
    assertThat(turno).isNotNull();
    assertThat(turno.get("pergunta").asText()).isEqualTo(PERGUNTA);
    assertThat(turno.get("resposta").asText()).isEqualTo(primeira.get("resposta").asText());
    assertThat(turno.get("assinatura").asText()).startsWith("v1.");
    assertThat(primeira.has("perguntaInterpretada")).isFalse();
    int chamadas = modelo.promptsRecebidos().size();

    JsonNode segunda = perguntar("tem certeza?", List.of(turno), false);

    assertThat(segunda.get("resposta").asText())
        .startsWith(Autocorrecao.REPETI_IGUAL.formatted(
            "compras de fertilizante de 01/01/2026 a 30/06/2026"));
    assertThat(segunda.get("perguntaInterpretada").asText()).isEqualTo(PERGUNTA);
    assertThat(segunda.get("rota").asText()).isEqualTo("DADOS");
    assertThat(segunda.get("turno")).isNotNull();
    assertThat(modelo.promptsRecebidos()).hasSize(chamadas);
  }

  @Test
  @DisplayName("Atalho de dados e 'tem certeza?': reexecuta a função fixa sem o modelo")
  void confirmacaoDepoisDeAtalho() throws Exception {
    JsonNode atalho = perguntar("Quanto eu tenho que pagar este mês?", List.of(), false);
    assertThat(atalho.get("turno")).isNotNull();
    int chamadas = modelo.promptsRecebidos().size();

    JsonNode segunda = perguntar("tem certeza?", List.of(atalho.get("turno")), false);

    assertThat(segunda.get("resposta").asText())
        .startsWith(Autocorrecao.REPETI_IGUAL.formatted("os vencimentos do mês atual"));
    assertThat(modelo.promptsRecebidos()).hasSize(chamadas);
  }

  @Test
  @DisplayName("Sem histórico, ou com a última resposta sem turno: o SEGUIMENTO novo")
  void seguimentoNovo() throws Exception {
    JsonNode primeira = perguntar(PERGUNTA, List.of(), false);

    assertThat(perguntar("tem certeza?", List.of(), false).get("resposta").asText())
        .contains(ConversaLeve.SEGUIMENTO_CONTINUIDADE);
    assertThat(perguntar("tem certeza?", List.of(primeira.get("turno")), true)
        .get("resposta").asText()).contains(ConversaLeve.SEGUIMENTO_CONTINUIDADE);
  }

  @Test
  @DisplayName("Par adulterado ou de outro produtor: histórico ignorado, nunca 400")
  void parAdulteradoEhIgnorado() throws Exception {
    JsonNode turno = perguntar(PERGUNTA, List.of(), false).get("turno");
    Map<String, Object> adulterado = new HashMap<>(JSON.convertValue(turno, Map.class));
    adulterado.put("resposta", "Você comprou R$ 1,00 de fertilizante.");

    assertThat(perguntar("tem certeza?", List.of(JSON.valueToTree(adulterado)), false)
        .get("resposta").asText()).contains(ConversaLeve.SEGUIMENTO_CONTINUIDADE);

    Produtor outro = produtorRepository.save(Produtor.builder()
        .nomeCompleto("Fazenda B").cpfCnpj("11122233344").estado("PR").build());
    String corpo = JSON.writeValueAsString(Map.of("pergunta", "tem certeza?",
        "historico", List.of(turno)));
    String resposta = mockMvc.perform(post("/api/assistente/chat")
            .header(MockIdentidadeFilter.HEADER_USUARIO_ID, outro.getId())
            .contentType(MediaType.APPLICATION_JSON).content(corpo))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    assertThat(JSON.readTree(resposta).get("resposta").asText())
        .contains(ConversaLeve.SEGUIMENTO_CONTINUIDADE);
  }

  @Test
  @DisplayName("Mais de 3 itens: 400 de corpo ilegível; item nulo: 400 em historico[0]")
  void validacaoDoHistorico() throws Exception {
    Map<String, String> item = Map.of("pergunta", "p", "resposta", "r", "assinatura", "v1.a.b");
    List<Object> mil = new ArrayList<>();
    for (int i = 0; i < 1000; i++) {
      mil.add(item);
    }
    mockMvc.perform(post("/api/assistente/chat")
            .header(MockIdentidadeFilter.HEADER_USUARIO_ID, produtor.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(JSON.writeValueAsString(Map.of("pergunta", "oi", "historico", mil))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.length()").value(1));

    List<Object> comNulo = new ArrayList<>();
    comNulo.add(null);
    mockMvc.perform(post("/api/assistente/chat")
            .header(MockIdentidadeFilter.HEADER_USUARIO_ID, produtor.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(JSON.writeValueAsString(Map.of("pergunta", "oi", "historico", comNulo))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("historico[0]"))
        .andExpect(jsonPath("$.errors[0].message").value("Item do histórico vazio."))
        .andExpect(jsonPath("$.errors[0].rejectedValue").doesNotExist());

    mockMvc.perform(post("/api/assistente/chat")
            .header(MockIdentidadeFilter.HEADER_USUARIO_ID, produtor.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(JSON.writeValueAsString(Map.of("pergunta", "oi", "historico",
                List.of(Map.of("pergunta", "p", "resposta", "x".repeat(6001),
                    "assinatura", "v1.a.b"))))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("historico[0].resposta"))
        .andExpect(jsonPath("$.errors[0].rejectedValue").doesNotExist());
  }

  @Test
  @DisplayName("O maior corpo legítimo (75 544 bytes) passa do filtro de tamanho")
  void corpoGrandePassa() throws Exception {
    // Tres bytes por unidade UTF-16 (o euro), como na conta da #499.
    String pergunta = "€".repeat(1000);
    List<Object> historico = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      historico.add(Map.of("pergunta", "€".repeat(1000), "resposta", "€".repeat(6000),
          "assinatura", "a".repeat(2048)));
    }
    byte[] corpo = JSON.writeValueAsBytes(Map.of("pergunta", pergunta, "historico", historico));
    assertThat(corpo.length).isLessThanOrEqualTo(LimiteCorpoAssistenteFilter.LIMITE_BYTES);
    assertThat(corpo.length).isGreaterThan(70_000);
    // Tres bytes por unidade UTF-16 (o euro), como na conta da #499.

    mockMvc.perform(post("/api/assistente/chat")
            .header(MockIdentidadeFilter.HEADER_USUARIO_ID, produtor.getId())
            .contentType(MediaType.APPLICATION_JSON).content(corpo))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("/uso: conversaVaiAoProvedor falso com o Ollama local")
  void usoSemProvedorPorApi() throws Exception {
    mockMvc.perform(get("/api/assistente/uso")
            .header(MockIdentidadeFilter.HEADER_USUARIO_ID, produtor.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.conversaVaiAoProvedor").value(false));
  }

  private JsonNode perguntar(String pergunta, List<JsonNode> historico, boolean semTurno)
      throws Exception {
    Map<String, Object> corpo = new HashMap<>();
    corpo.put("pergunta", pergunta);
    if (!historico.isEmpty()) {
      corpo.put("historico", historico);
    }
    if (semTurno) {
      corpo.put("ultimaRespostaSemTurno", true);
    }
    String resposta = mockMvc.perform(post("/api/assistente/chat")
            .header(MockIdentidadeFilter.HEADER_USUARIO_ID, produtor.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(JSON.writeValueAsString(corpo)))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    return JSON.readTree(resposta);
  }
}
