package br.com.agrocontabil.support;

import br.com.agrocontabil.assistente.PromptsCeres;
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
  private final List<Prompt> classificacoes = new CopyOnWriteArrayList<>();
  private volatile String classificacao;
  private final Deque<ChatResponse> fila = new ArrayDeque<>();
  private Function<Prompt, ChatResponse> responder = prompt -> texto(RESPOSTA_PADRAO);

  private volatile Runnable aoReiniciar = () -> { };

  /** Ação extra do {@link #reiniciar()} (fecha o disjuntor do modo fallback no contexto, #146). */
  public void aoReiniciar(Runnable acao) {
    this.aoReiniciar = acao;
  }

  /** Volta ao comportamento padrão e esquece os prompts recebidos. */
  public synchronized void reiniciar() {
    aoReiniciar.run();
    prompts.clear();
    classificacoes.clear();
    classificacao = null;
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

  /**
   * Responde o prompt do classificador de intenção (issue #157) com esta intenção e confiança
   * 0,95, sem consumir o comportamento programado para as rotas. As chamadas de classificação vão
   * para {@link #classificacoesRecebidas()}, não para {@link #promptsRecebidos()}.
   *
   * @param intencao nome da intenção (DADOS, LEGISLACAO, MISTO, FORA_DE_ESCOPO)
   */
  public void classificarComo(String intencao) {
    this.classificacao = intencao;
  }

  /** Prompts de classificação recebidos (só quando {@link #classificarComo} está ativo). */
  public List<Prompt> classificacoesRecebidas() {
    return List.copyOf(classificacoes);
  }

  /** Prompts recebidos, na ordem das chamadas. */
  public List<Prompt> promptsRecebidos() {
    return List.copyOf(prompts);
  }

  @Override
  public ChatResponse call(Prompt prompt) {
    String intencao = classificacao;
    if (intencao != null && ehClassificacao(prompt)) {
      classificacoes.add(prompt);
      return texto("{\"intencao\": \"" + intencao + "\", \"confianca\": 0.95}");
    }
    prompts.add(prompt);
    Function<Prompt, ChatResponse> comportamento;
    synchronized (this) {
      ChatResponse proxima = fila.poll();
      if (proxima != null) {
        return proxima;
      }
      comportamento = responder;
    }
    // Fora do lock: um comportamento que bloqueia (modelo "pensando") nao trava outras chamadas.
    return comportamento.apply(prompt);
  }

  @Override
  public Flux<ChatResponse> stream(Prompt prompt) {
    return Flux.defer(() -> Flux.just(call(prompt)));
  }

  @Override
  public ChatOptions getDefaultOptions() {
    return ChatOptions.builder().model("simulado").build();
  }

  private static boolean ehClassificacao(Prompt prompt) {
    return prompt.getSystemMessage() != null
        && PromptsCeres.CLASSIFICACAO.equals(prompt.getSystemMessage().getText());
  }

  /** Resposta de texto simples do modelo. */
  public static ChatResponse texto(String conteudo) {
    return new ChatResponse(List.of(new Generation(new AssistantMessage(conteudo))));
  }

  /**
   * O modelo pedindo funções, todas na mesma resposta (#266: a rota de dados tem uma chamada só).
   *
   * @param chamadas pares nome da função → argumentos, na ordem
   */
  @SafeVarargs
  public static ChatResponse funcoes(java.util.Map.Entry<String, java.util.Map<String, Object>>...
      chamadas) {
    List<AssistantMessage.ToolCall> pedidos = new java.util.ArrayList<>();
    try {
      for (int i = 0; i < chamadas.length; i++) {
        pedidos.add(new AssistantMessage.ToolCall("chamada-" + (i + 1), "function",
            chamadas[i].getKey(), JSON.writeValueAsString(chamadas[i].getValue())));
      }
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
    return new ChatResponse(List.of(new Generation(
        AssistantMessage.builder().content("").toolCalls(pedidos).build())));
  }

  /** O modelo pedindo uma função. */
  public static ChatResponse funcao(String nome, java.util.Map<String, Object> argumentos) {
    return funcoes(java.util.Map.entry(nome, argumentos));
  }

  private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
      new com.fasterxml.jackson.databind.ObjectMapper();
}
