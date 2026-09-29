package br.com.agrocontabil.support;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * {@link ChatModel} programável do profile {@code test} (issue #141): a CI não depende do Ollama.
 *
 * <p>Por padrão responde {@link #RESPOSTA_PADRAO}. O teste programa o comportamento com
 * {@link #responderTexto(String)}, {@link #responderEmSequencia(ChatResponse...)} (uma resposta por
 * chamada — é assim que se simula o modelo pedindo uma ferramenta e depois redigindo),
 * {@link #responderCom(Function)} ou {@link #falharCom(RuntimeException)}, e inspeciona os prompts
 * recebidos em {@link #promptsRecebidos()}.
 *
 * <p>É um bean de contexto, reaproveitado entre testes pelo cache de contexto do Spring: todo teste
 * que o programa deve chamar {@link #reiniciar()} no {@code @BeforeEach}.
 */
public class ChatModelSimulado implements ChatModel {

  /** Texto devolvido quando nenhum comportamento foi programado. */
  public static final String RESPOSTA_PADRAO = "resposta simulada";

  private final List<Prompt> prompts = new CopyOnWriteArrayList<>();
  private final Deque<ChatResponse> fila = new ArrayDeque<>();
  private Function<Prompt, ChatResponse> responder = prompt -> texto(RESPOSTA_PADRAO);

  /** Volta ao comportamento padrão e esquece os prompts recebidos. */
  public synchronized void reiniciar() {
    prompts.clear();
    fila.clear();
    responder = prompt -> texto(RESPOSTA_PADRAO);
  }

  /** Toda chamada devolve este texto. */
  public synchronized void responderTexto(String conteudo) {
    fila.clear();
    responder = prompt -> texto(conteudo);
  }

  /** Cada chamada consome a próxima resposta; esgotada a fila, volta ao padrão. */
  public synchronized void responderEmSequencia(ChatResponse... respostas) {
    fila.clear();
    fila.addAll(List.of(respostas));
    responder = prompt -> texto(RESPOSTA_PADRAO);
  }

  /** Resposta calculada a partir do prompt recebido. */
  public synchronized void responderCom(Function<Prompt, ChatResponse> funcao) {
    fila.clear();
    responder = funcao;
  }

  /** Toda chamada lança esta exceção (simula provedor fora do ar, timeout, chave recusada). */
  public synchronized void falharCom(RuntimeException falha) {
    fila.clear();
    responder = prompt -> {
      throw falha;
    };
  }

  /** Prompts recebidos, na ordem das chamadas. */
  public List<Prompt> promptsRecebidos() {
    return List.copyOf(prompts);
  }

  @Override
  public ChatResponse call(Prompt prompt) {
    prompts.add(prompt);
    synchronized (this) {
      ChatResponse proxima = fila.poll();
      return proxima != null ? proxima : responder.apply(prompt);
    }
  }

  @Override
  public Flux<ChatResponse> stream(Prompt prompt) {
    return Flux.defer(() -> Flux.just(call(prompt)));
  }

  @Override
  public ChatOptions getDefaultOptions() {
    return ChatOptions.builder().model("simulado").build();
  }

  /** Resposta de texto simples do modelo. */
  public static ChatResponse texto(String conteudo) {
    return new ChatResponse(List.of(new Generation(new AssistantMessage(conteudo))));
  }
}
