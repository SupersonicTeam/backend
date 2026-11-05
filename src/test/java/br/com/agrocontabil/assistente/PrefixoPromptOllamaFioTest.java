package br.com.agrocontabil.assistente;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.assistente.ferramentas.FerramentasDadosProdutor;
import br.com.agrocontabil.assistente.ferramentas.ProcessadorErroFerramenta;
import br.com.agrocontabil.assistente.llm.DisponibilidadeModelo;
import br.com.agrocontabil.assistente.llm.ModeloLinguagem;
import br.com.agrocontabil.assistente.orquestracao.LlmClassificadorIntencao;
import br.com.agrocontabil.assistente.porta.DadosProdutorPorta;
import br.com.agrocontabil.assistente.porta.TratamentoProdutoPorta;
import br.com.agrocontabil.assistente.rag.BuscaLegislacaoService;
import br.com.agrocontabil.assistente.rag.TrechoRecuperado;
import br.com.agrocontabil.assistente.rag.ValidadorCitacao;
import br.com.agrocontabil.config.AssistenteProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * Cache do prefixo do prompt (#270), verificado <strong>no fio</strong>, com o
 * {@link OllamaChatModel} real: o Ollama só reaproveita o processamento do prompt (o prefill, o
 * gargalo na VPS sem GPU) até o primeiro byte diferente da requisição anterior. Então a parte fixa
 * de cada chamada — instrução de sistema e definições das funções — tem de ser igual byte a byte
 * entre perguntas diferentes, em dias diferentes, e vir antes de tudo que varia (data, pergunta,
 * trechos de lei).
 *
 * <p>E cada chamada leva só as funções que pode usar: a de dados, as de dados; a classificação e a
 * lei, nenhuma.
 */
class PrefixoPromptOllamaFioTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final LocalDate DIA_1 = LocalDate.of(2026, 9, 29);
  private static final LocalDate DIA_2 = LocalDate.of(2026, 11, 5);

  private HttpServer servidor;
  private final List<String> corpos = new CopyOnWriteArrayList<>();
  private final List<String> respostas = new CopyOnWriteArrayList<>();
  private ModeloLinguagem modelo;

  @BeforeEach
  void setUp() throws IOException {
    servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    servidor.createContext("/api/chat", troca -> {
      corpos.add(new String(troca.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      byte[] corpo = respostas.remove(0).getBytes(StandardCharsets.UTF_8);
      troca.getResponseHeaders().add("Content-Type", "application/json");
      troca.sendResponseHeaders(200, corpo.length);
      try (OutputStream saida = troca.getResponseBody()) {
        saida.write(corpo);
      }
    });
    servidor.start();

    OllamaChatModel ollama = OllamaChatModel.builder()
        .ollamaApi(OllamaApi.builder()
            .baseUrl("http://127.0.0.1:" + servidor.getAddress().getPort()).build())
        .defaultOptions(OllamaChatOptions.builder().model("qwen3:4b-instruct").build())
        .build();
    StaticListableBeanFactory beans = new StaticListableBeanFactory();
    beans.addBean("ollama", ollama);
    modelo = new ModeloLinguagem(beans.getBeanProvider(ChatModel.class), "ollama",
        new DisponibilidadeModelo(Duration.ofSeconds(30), System::nanoTime));
  }

  @AfterEach
  void tearDown() {
    servidor.stop(0);
  }

  @Test
  @DisplayName("Dados: sistema + funcoes iguais byte a byte entre perguntas e dias diferentes")
  void prefixoDaRotaDeDadosEstavel() throws IOException {
    respostas.add(ollama(""));
    respostas.add(ollama(""));

    // Duas instancias da rota: a ordem das funcoes nao pode depender da reflexao de cada uma.
    novaRotaDados().responder("Qual meu saldo de IBS em julho?",
        new ContextoAssistente(UUID.randomUUID(), DIA_1));
    novaRotaDados().responder("Quanto gastei com adubo este ano?",
        new ContextoAssistente(UUID.randomUUID(), DIA_2));

    JsonNode primeira = JSON.readTree(corpos.get(0));
    JsonNode segunda = JSON.readTree(corpos.get(1));
    assertThat(primeira.get("messages").get(0)).isEqualTo(segunda.get("messages").get(0));
    assertThat(primeira.get("tools")).isEqualTo(segunda.get("tools"));
    assertThat(primeira.get("messages").get(0).get("role").asText()).isEqualTo("system");
    assertThat(primeira.get("messages").get(0).get("content").asText())
        .isEqualTo(PromptsCeres.SISTEMA_DADOS)
        .doesNotContain("2026");

    // Os nomes em ordem: so as funcoes de dados, nenhuma outra.
    List<String> nomes = new ArrayList<>();
    primeira.get("tools").forEach(t -> nomes.add(t.get("function").get("name").asText()));
    assertThat(nomes).isSorted().hasSize(13).contains("consultarApuracao", "resumoDoProdutor");

    // A parte variavel vem depois: data e pergunta na mensagem do usuario.
    assertThat(primeira.get("messages").get(1).get("content").asText())
        .isEqualTo("Hoje e 2026-09-29.\n\nPergunta: Qual meu saldo de IBS em julho?");
    assertThat(segunda.get("messages").get(1).get("content").asText())
        .startsWith("Hoje e 2026-11-05.");
  }

  @Test
  @DisplayName("Classificacao: sem funcoes e com o sistema constante")
  void classificacaoSemFuncoes() throws IOException {
    respostas.add(ollama("{\\\"intencao\\\": \\\"DADOS\\\", \\\"confianca\\\": 0.9}"));
    respostas.add(ollama("{\\\"intencao\\\": \\\"LEGISLACAO\\\", \\\"confianca\\\": 0.9}"));
    LlmClassificadorIntencao classificador = new LlmClassificadorIntencao(modelo, JSON);

    classificador.classificar("Quanto paguei de CBS?",
        new ContextoAssistente(UUID.randomUUID(), DIA_1));
    classificador.classificar("O que diz o art. 138?",
        new ContextoAssistente(UUID.randomUUID(), DIA_2));

    JsonNode primeira = JSON.readTree(corpos.get(0));
    JsonNode segunda = JSON.readTree(corpos.get(1));
    assertThat(semFuncoes(primeira)).isTrue();
    assertThat(primeira.get("messages").get(0)).isEqualTo(segunda.get("messages").get(0));
    assertThat(primeira.get("messages").get(0).get("content").asText())
        .isEqualTo(PromptsCeres.CLASSIFICACAO);
  }

  @Test
  @DisplayName("Lei: sem funcoes; regras antes da data e dos trechos, iguais entre perguntas")
  void prefixoDaRotaDeLegislacaoEstavel() throws IOException {
    BuscaLegislacaoService busca = mock(BuscaLegislacaoService.class);
    when(busca.buscar(anyString(), any())).thenReturn(
        List.of(trecho("T1", "id-1", "Os insumos agropecuarios tem reducao.")),
        List.of(trecho("T1", "id-2", "O produtor rural pode optar pelo regime regular.")));
    RotaLegislacao rota = new RotaLegislacao(modelo, busca, new ValidadorCitacao(),
        new ValidadorNumeros(), new VerificadorAfirmacaoDados());
    respostas.add(ollama("Os insumos tem reducao [T1]."));
    respostas.add(ollama("O produtor pode optar [T1]."));

    rota.responder("Insumo tem reducao?", new ContextoAssistente(UUID.randomUUID(), DIA_1));
    rota.responder("Posso optar pelo regime regular?",
        new ContextoAssistente(UUID.randomUUID(), DIA_2));

    JsonNode primeira = JSON.readTree(corpos.get(0));
    JsonNode segunda = JSON.readTree(corpos.get(1));
    assertThat(semFuncoes(primeira)).isTrue();
    String sistema1 = primeira.get("messages").get(0).get("content").asText();
    String sistema2 = segunda.get("messages").get(0).get("content").asText();
    String fixo1 = sistema1.substring(0, sistema1.indexOf("Hoje e "));
    String fixo2 = sistema2.substring(0, sistema2.indexOf("Hoje e "));
    assertThat(fixo1).isEqualTo(fixo2).doesNotContain("2026", "insumos agropecuarios");
    // Tudo que varia fica depois da parte fixa.
    assertThat(sistema1.substring(fixo1.length()))
        .startsWith("Hoje e 2026-09-29.").contains("Os insumos agropecuarios tem reducao.");
  }

  /** O Spring AI manda {@code "tools": []} quando não há função: vazio conta como nenhuma. */
  private static boolean semFuncoes(JsonNode corpo) {
    return !corpo.has("tools") || corpo.get("tools").isNull() || corpo.get("tools").isEmpty();
  }

  private RotaDados novaRotaDados() {
    return new RotaDados(modelo, new ValidadorNumeros(), new VerificadorNegativaDados(),
        new FerramentasDadosProdutor(mock(DadosProdutorPorta.class),
            mock(TratamentoProdutoPorta.class)),
        new ProcessadorErroFerramenta(), new AssistenteProperties(),
        new StaticListableBeanFactory().getBeanProvider(ObservationRegistry.class));
  }

  private static TrechoRecuperado trecho(String marca, String id, String texto) {
    return new TrechoRecuperado(marca, id, "LC 214/2025", "art. 138", "https://exemplo", texto,
        0.9);
  }

  private static String ollama(String conteudo) {
    return "{\"model\":\"qwen3:4b-instruct\",\"created_at\":\"2026-09-29T12:00:00Z\","
        + "\"message\":{\"role\":\"assistant\",\"content\":\"" + conteudo + "\"},"
        + "\"done_reason\":\"stop\",\"done\":true,\"total_duration\":1,\"load_duration\":1,"
        + "\"prompt_eval_count\":10,\"prompt_eval_duration\":1,\"eval_count\":5,"
        + "\"eval_duration\":1}";
  }
}
