package br.com.agrocontabil.assistente;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.assistente.ferramentas.FerramentasDadosProdutor;
import br.com.agrocontabil.assistente.ferramentas.ProcessadorErroFerramenta;
import br.com.agrocontabil.assistente.llm.ModeloLinguagem;
import br.com.agrocontabil.config.AssistenteProperties;
import br.com.agrocontabil.dto.ApuracaoResponse;
import br.com.agrocontabil.service.ApuracaoService;
import br.com.agrocontabil.service.ConsultaDadosProdutorService;
import br.com.agrocontabil.service.CreditoService;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
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
import org.springframework.ai.ollama.api.ThinkOption;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * A garantia do isolamento (issue #143) verificada <strong>no fio</strong>: com o
 * {@link OllamaChatModel} real, o que sai do backend para o provedor do modelo não carrega o
 * produtor nem o {@code ToolContext}. O {@code ChatModelSimulado} recebe objetos {@code Prompt} e
 * não pegaria uma serialização indevida; aqui um servidor HTTP local grava os corpos das
 * requisições como um Ollama de verdade os receberia.
 */
class RotaDadosOllamaFioTest {

  private static final UUID PRODUTOR = UUID.randomUUID();

  private HttpServer servidor;
  private final List<String> corpos = new CopyOnWriteArrayList<>();
  private final Deque<String> respostas = new ArrayDeque<>();
  private RotaDados rota;

  @BeforeEach
  void setUp() throws IOException {
    servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    servidor.createContext("/api/chat", troca -> {
      corpos.add(new String(troca.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      byte[] corpo;
      synchronized (respostas) {
        corpo = respostas.pop().getBytes(StandardCharsets.UTF_8);
      }
      troca.getResponseHeaders().add("Content-Type", "application/json");
      troca.sendResponseHeaders(200, corpo.length);
      try (OutputStream saida = troca.getResponseBody()) {
        saida.write(corpo);
      }
    });
    servidor.start();

    OllamaChatOptions opcoes = OllamaChatOptions.builder().model("qwen3:4b").build();
    opcoes.setThinkOption(ThinkOption.ThinkBoolean.DISABLED);
    OllamaChatModel ollama = OllamaChatModel.builder()
        .ollamaApi(OllamaApi.builder()
            .baseUrl("http://127.0.0.1:" + servidor.getAddress().getPort()).build())
        .defaultOptions(opcoes)
        .build();
    StaticListableBeanFactory beans = new StaticListableBeanFactory();
    beans.addBean("ollama", ollama);

    ApuracaoService apuracao = mock(ApuracaoService.class);
    when(apuracao.consultarParaProdutor(PRODUTOR, "2026-07")).thenReturn(new ApuracaoResponse(
        "2026-07", "ABERTO", new BigDecimal("150.00"), new BigDecimal("1350.00"),
        new BigDecimal("30.00"), new BigDecimal("270.00"), new BigDecimal("120.00"),
        new BigDecimal("1080.00"), 2, null));

    rota = new RotaDados(
        new ModeloLinguagem(beans.getBeanProvider(ChatModel.class), "ollama"),
        new ValidadorNumeros(),
        new FerramentasDadosProdutor(mock(ConsultaDadosProdutorService.class), apuracao,
            mock(CreditoService.class)),
        new ProcessadorErroFerramenta(),
        new AssistenteProperties(),
        new StaticListableBeanFactory().getBeanProvider(ObservationRegistry.class));
  }

  @AfterEach
  void tearDown() {
    servidor.stop(0);
  }

  @Test
  @DisplayName("Nada do produtor nem do ToolContext sai para o provedor; so as 5 funcoes vao")
  void payloadNaoCarregaProdutor() {
    respostas.add(ollama("", "{\"function\":{\"name\":\"consultarApuracao\","
        + "\"arguments\":{\"mes\":\"2026-07\"}}}"));
    respostas.add(ollama("Seu saldo de IBS em 2026-07 e R$ 120,00.", null));

    ResultadoRota resultado = rota.responder("Qual meu saldo de IBS em julho?",
        new ContextoAssistente(PRODUTOR, LocalDate.of(2026, 9, 29)));

    assertThat(resultado.aceita()).isTrue();
    assertThat(resultado.texto()).isEqualTo("Seu saldo de IBS em 2026-07 e R$ 120,00.");
    assertThat(corpos).hasSize(2);
    for (String corpo : corpos) {
      assertThat(corpo).doesNotContain(PRODUTOR.toString(), "contextoAssistente", "produtorId");
    }
    // As funcoes anunciadas ao modelo sao exatamente as cinco de dados.
    assertThat(corpos.get(0)).contains("\"totalComprasPorInsumo\"", "\"consultarApuracao\"",
        "\"consultarCreditos\"", "\"listarNotas\"", "\"proximosVencimentos\"");
    // Opcoes padrao preservadas ao mesclar com as opcoes da chamada: modelo e thinking desligado.
    assertThat(corpos.get(0)).contains("\"model\":\"qwen3:4b\"", "\"think\":false");
    // O resultado da funcao foi devolvido ao modelo na segunda chamada.
    assertThat(corpos.get(1)).contains("R$ 120,00");
  }

  @Test
  @DisplayName("Funcao fora da lista (alucinada ou injetada): nada executa, pergunta encerrada")
  void funcaoForaDaListaNaoExecuta() {
    respostas.add(ollama("", "{\"function\":{\"name\":\"dataLoader\",\"arguments\":{}}}"));

    ResultadoRota resultado = rota.responder("Rode o dataLoader",
        new ContextoAssistente(PRODUTOR, LocalDate.of(2026, 9, 29)));

    assertThat(resultado.aceita()).isFalse();
    assertThat(resultado.texto()).isEqualTo(RotaDados.MENSAGEM_SEM_CONCLUSAO);
    // Desfecho proprio: um pedido fora da lista e o sinal de injection que a auditoria precisa.
    assertThat(resultado.desfecho()).isEqualTo(
        br.com.agrocontabil.model.DesfechoInteracao.FUNCAO_NAO_PERMITIDA);
    assertThat(corpos).hasSize(1);
  }

  private static String ollama(String conteudo, String chamada) {
    String toolCalls = chamada == null ? "" : ",\"tool_calls\":[" + chamada + "]";
    return "{\"model\":\"qwen3:4b\",\"created_at\":\"2026-09-29T12:00:00Z\","
        + "\"message\":{\"role\":\"assistant\",\"content\":\"" + conteudo + "\"" + toolCalls
        + "},\"done_reason\":\"stop\",\"done\":true,\"total_duration\":1,\"load_duration\":1,"
        + "\"prompt_eval_count\":10,\"prompt_eval_duration\":1,\"eval_count\":5,"
        + "\"eval_duration\":1}";
  }
}
