package br.com.agrocontabil.support;

import br.com.agrocontabil.assistente.llm.DisponibilidadeModelo;
import br.com.agrocontabil.assistente.llm.ModeloLinguagem;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.ollama.api.ThinkOption;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * Servidor HTTP local no lugar do Ollama, para os testes que conferem <strong>o fio</strong>: o
 * {@link OllamaChatModel} real serializa a requisição como em produção, e o servidor grava os
 * corpos recebidos e devolve as respostas enfileiradas, na ordem. O {@link ChatModelSimulado}
 * recebe objetos {@code Prompt} e não pegaria uma serialização indevida.
 */
public final class OllamaHttpSimulado implements AutoCloseable {

  /** Modelo das opções padrão, como no {@code application.yml}. */
  public static final String MODELO = "qwen3:4b";

  private final HttpServer servidor;
  private final List<String> corpos = new CopyOnWriteArrayList<>();
  private final Deque<String> respostas = new ArrayDeque<>();
  private final ModeloLinguagem modelo;

  private OllamaHttpSimulado() throws IOException {
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

    // Opcoes padrao como as de producao: o modelo e o thinking desligado.
    OllamaChatOptions opcoes = OllamaChatOptions.builder().model(MODELO).build();
    opcoes.setThinkOption(ThinkOption.ThinkBoolean.DISABLED);
    OllamaChatModel ollama = OllamaChatModel.builder()
        .ollamaApi(OllamaApi.builder()
            .baseUrl("http://127.0.0.1:" + servidor.getAddress().getPort()).build())
        .defaultOptions(opcoes)
        .build();
    StaticListableBeanFactory beans = new StaticListableBeanFactory();
    beans.addBean("ollama", ollama);
    modelo = new ModeloLinguagem(beans.getBeanProvider(ChatModel.class), "ollama",
        new DisponibilidadeModelo(Duration.ofSeconds(30), System::nanoTime));
  }

  /** Sobe o servidor numa porta livre. */
  public static OllamaHttpSimulado iniciar() throws IOException {
    return new OllamaHttpSimulado();
  }

  /** Porta do modelo ligada a este servidor. */
  public ModeloLinguagem modelo() {
    return modelo;
  }

  /** Corpos JSON das requisições recebidas, na ordem. */
  public List<String> corpos() {
    return corpos;
  }

  /** Enfileira a próxima resposta (JSON de {@code /api/chat}). */
  public void responder(String json) {
    synchronized (respostas) {
      respostas.add(json);
    }
  }

  /**
   * Resposta do {@code /api/chat}.
   *
   * @param conteudo   texto da mensagem, já escapado para JSON
   * @param chamada    JSON de uma chamada de função ({@code {"function":{...}}}), ou {@code null}
   * @param doneReason motivo do fim ({@code stop}; {@code length} = cortada pelo teto)
   * @return corpo JSON
   */
  public static String resposta(String conteudo, String chamada, String doneReason) {
    String toolCalls = chamada == null ? "" : ",\"tool_calls\":[" + chamada + "]";
    return "{\"model\":\"" + MODELO + "\",\"created_at\":\"2026-09-29T12:00:00Z\","
        + "\"message\":{\"role\":\"assistant\",\"content\":\"" + conteudo + "\"" + toolCalls
        + "},\"done_reason\":\"" + doneReason + "\",\"done\":true,\"total_duration\":1,"
        + "\"load_duration\":1,\"prompt_eval_count\":10,\"prompt_eval_duration\":1,"
        + "\"eval_count\":5,\"eval_duration\":1}";
  }

  /** Resposta encerrada normalmente ({@code done_reason: stop}). */
  public static String resposta(String conteudo, String chamada) {
    return resposta(conteudo, chamada, "stop");
  }

  @Override
  public void close() {
    servidor.stop(0);
  }
}
