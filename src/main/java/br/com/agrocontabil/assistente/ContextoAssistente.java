package br.com.agrocontabil.assistente;

import br.com.agrocontabil.model.DesfechoInteracao;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Contexto de uma pergunta à Ceres, entregue às funções pelo {@link ToolContext} (issue #143).
 *
 * <p><strong>É daqui, e só daqui, que as funções tiram o produtor.</strong> O {@code produtorId}
 * é resolvido a partir do produtor autenticado, na thread da requisição, <em>antes</em> de o
 * modelo ser chamado. As funções não têm parâmetro de produtor (o modelo não tem como pedir dados
 * de outro, nem sob prompt injection: não se contorna um parâmetro que não existe) e não leem o
 * {@code SecurityContext} (com streaming elas rodam em outra thread, onde ele chega vazio).
 *
 * <p>O {@link ToolContext} não é enviado ao modelo: fica no backend.
 *
 * <p>Também coleta as {@link FonteDado fontes de dados} usadas na resposta — a lista que o
 * frontend mostra como "calculado a partir das suas notas". Vive só durante a pergunta.
 *
 * <p>Carrega ainda o sinal de <strong>interrupção</strong> (issue #157): quando a pergunta estoura
 * o tempo total ou o cliente do streaming desconecta, quem espera marca o contexto e as rotas param
 * antes da próxima chamada ao modelo ou às funções ({@link #verificarAtiva()}).
 */
public final class ContextoAssistente {

  /** Chave do contexto dentro do {@link ToolContext}. */
  public static final String CHAVE = "agrocontabil.contextoAssistente";

  /** Caracteres por token na estimativa, quando o provedor não informa o uso (issue #166). */
  static final int CARACTERES_POR_TOKEN = 4;

  private final UUID produtorId;
  private final LocalDate hoje;
  private final List<FonteDado> fontes = Collections.synchronizedList(new ArrayList<>());
  private final AtomicInteger tokensEntrada = new AtomicInteger();
  private final AtomicInteger tokensSaida = new AtomicInteger();
  private final AtomicBoolean usoInformado = new AtomicBoolean();
  private final AtomicBoolean tokensEstimados = new AtomicBoolean();
  private final AtomicInteger chamadasDeFuncao = new AtomicInteger();
  private final AtomicInteger consultasComResultado = new AtomicInteger();
  private final List<String> trechosCitados = Collections.synchronizedList(new ArrayList<>());
  private final AtomicReference<DesfechoInteracao> interrupcao = new AtomicReference<>();
  private final AtomicReference<DesfechoInteracao> desfechoDados = new AtomicReference<>();
  private final AtomicReference<DesfechoInteracao> desfechoLegislacao = new AtomicReference<>();
  private final AtomicReference<Boolean> perguntaLeiReescrita = new AtomicReference<>();

  /**
   * Cria o contexto de uma pergunta.
   *
   * @param produtorId produtor autenticado (nunca vindo do cliente nem do modelo)
   * @param hoje       data de referência da pergunta em {@code America/Sao_Paulo}
   */
  public ContextoAssistente(UUID produtorId, LocalDate hoje) {
    this.produtorId = Objects.requireNonNull(produtorId, "produtorId");
    this.hoje = Objects.requireNonNull(hoje, "hoje");
  }

  /**
   * Recupera o contexto do {@link ToolContext} de uma função. <strong>Falha fechado:</strong> sem
   * contexto a função não consulta nada — nunca cai num produtor padrão.
   *
   * @param toolContext contexto recebido pela função
   * @return o contexto da pergunta
   * @throws ContextoAssistenteAusenteException se o contexto não estiver presente
   */
  public static ContextoAssistente de(ToolContext toolContext) {
    Object contexto = toolContext == null ? null : toolContext.getContext().get(CHAVE);
    if (contexto instanceof ContextoAssistente assistente) {
      return assistente;
    }
    throw new ContextoAssistenteAusenteException();
  }

  /** O {@link ToolContext} a passar nas opções da chamada ao modelo. */
  public Map<String, Object> comoToolContext() {
    return Map.of(CHAVE, this);
  }

  /**
   * Registra que uma função foi usada, com os parâmetros já validados e normalizados.
   *
   * <p>{@code vazio} é obrigatório de propósito (issue #170): no MISTO, a parte de dados sem nada
   * encontrado vira frase fixa do backend ("não encontrei..."). Se o sinal fosse opcional, uma
   * função nova que esquecesse de informá-lo teria o resultado real trocado por essa frase falsa.
   *
   * @param funcao     nome da função
   * @param parametros parâmetros efetivamente usados na consulta
   * @param vazio      {@code true} se a consulta não encontrou nada (sem nota, guia ou compra)
   */
  public void registrarFonte(String funcao, Map<String, String> parametros, boolean vazio) {
    fontes.add(new FonteDado(funcao, Collections.unmodifiableMap(new LinkedHashMap<>(parametros))));
    if (!vazio) {
      consultasComResultado.incrementAndGet();
    }
  }

  /**
   * Soma o uso de tokens de uma chamada ao modelo, para a auditoria (issue #144) e a cota semanal
   * (issue #166). Toda chamada conta, inclusive a classificação de intenção.
   *
   * <p>Provedor que não informa o uso (alguns só no fim do streaming, outros nunca) tem os tokens
   * <strong>estimados</strong> por caracteres ({@value #CARACTERES_POR_TOKEN} por token) a partir
   * do prompt e da resposta, e a pergunta fica marcada como estimada na auditoria. Sem a
   * estimativa, um provedor que não informa uso deixaria a cota sem efeito.
   *
   * @param prompt   o que foi enviado ao modelo
   * @param resposta resposta do modelo
   */
  public void registrarUso(Prompt prompt, ChatResponse resposta) {
    Usage uso = resposta == null || resposta.getMetadata() == null
        ? null
        : resposta.getMetadata().getUsage();
    Integer entrada = uso == null ? null : uso.getPromptTokens();
    Integer saida = uso == null ? null : uso.getCompletionTokens();
    if (entrada == null || entrada <= 0) {
      entrada = estimarTokens(caracteresDoPrompt(prompt));
      tokensEstimados.set(true);
    }
    if (saida == null || saida <= 0) {
      saida = estimarTokens(caracteresDaResposta(resposta));
      tokensEstimados.set(true);
    }
    tokensEntrada.addAndGet(entrada);
    tokensSaida.addAndGet(saida);
    usoInformado.set(true);
  }

  /** {@code true} se algum token desta pergunta foi estimado, não informado pelo provedor. */
  public boolean tokensEstimados() {
    return tokensEstimados.get();
  }

  /** Tokens para uma quantidade de caracteres, arredondando para cima (no mínimo 1). */
  static int estimarTokens(long caracteres) {
    return (int) Math.max(1, (caracteres + CARACTERES_POR_TOKEN - 1) / CARACTERES_POR_TOKEN);
  }

  private static long caracteresDoPrompt(Prompt prompt) {
    if (prompt == null) {
      return 0;
    }
    long total = 0;
    // As definicoes das funcoes (nome, descricao, schema JSON) tambem vao ao modelo como entrada.
    if (prompt.getOptions() instanceof ToolCallingChatOptions opcoes
        && opcoes.getToolCallbacks() != null) {
      for (ToolCallback funcao : opcoes.getToolCallbacks()) {
        ToolDefinition definicao = funcao.getToolDefinition();
        total += definicao.name().length()
            + (definicao.description() == null ? 0 : definicao.description().length())
            + (definicao.inputSchema() == null ? 0 : definicao.inputSchema().length());
      }
    }
    for (Message mensagem : prompt.getInstructions()) {
      if (mensagem instanceof ToolResponseMessage respostas) {
        for (ToolResponseMessage.ToolResponse r : respostas.getResponses()) {
          total += r.responseData() == null ? 0 : r.responseData().length();
        }
      } else if (mensagem.getText() != null) {
        total += mensagem.getText().length();
      }
    }
    return total;
  }

  private static long caracteresDaResposta(ChatResponse resposta) {
    if (resposta == null || resposta.getResult() == null) {
      return 0;
    }
    AssistantMessage saida = resposta.getResult().getOutput();
    long total = saida.getText() == null ? 0 : saida.getText().length();
    if (saida.getToolCalls() != null) {
      for (AssistantMessage.ToolCall chamada : saida.getToolCalls()) {
        total += chamada.name().length()
            + (chamada.arguments() == null ? 0 : chamada.arguments().length());
      }
    }
    return total;
  }

  /** {@code true} se nenhuma consulta aos dados encontrou algo (inclusive se nenhuma rodou). */
  public boolean nenhumDadoEncontrado() {
    return consultasComResultado.get() == 0;
  }

  /** Conta as funções que o modelo pediu, inclusive as que falharem (issue #144). */
  public void registrarChamadasDeFuncao(int quantidade) {
    chamadasDeFuncao.addAndGet(quantidade);
  }

  /** Tokens de entrada somados (informados ou estimados); nulo se o modelo não foi chamado. */
  public Integer tokensEntrada() {
    return usoInformado.get() ? tokensEntrada.get() : null;
  }

  /** Tokens de saída somados (informados ou estimados); nulo se o modelo não foi chamado. */
  public Integer tokensSaida() {
    return usoInformado.get() ? tokensSaida.get() : null;
  }

  /**
   * Registra os ids dos trechos de lei citados na resposta (issue #156), para a auditoria.
   *
   * @param ids ids em {@code legislacao_trecho}
   */
  public void registrarTrechosCitados(List<String> ids) {
    trechosCitados.addAll(ids);
  }

  /** Ids dos trechos de lei citados, na ordem da citação. */
  public List<String> trechosCitados() {
    synchronized (trechosCitados) {
      return List.copyOf(trechosCitados);
    }
  }

  /**
   * Desfecho da parte de dados do MISTO, para a auditoria saber qual das duas falhou. Nas rotas de
   * uma parte só fica nulo: o desfecho da pergunta já é o dela.
   */
  public void registrarDesfechoDados(DesfechoInteracao desfecho) {
    desfechoDados.set(desfecho);
  }

  /**
   * Desfecho da parte de lei do MISTO.
   *
   * @param desfecho  como a parte terminou
   * @param reescrita {@code true} se ela recebeu a pergunta legal separada pelo classificador, e
   *                  não a pergunta inteira
   */
  public void registrarDesfechoLegislacao(DesfechoInteracao desfecho, boolean reescrita) {
    desfechoLegislacao.set(desfecho);
    perguntaLeiReescrita.set(reescrita);
  }

  /** Desfecho da parte de dados do MISTO; nulo fora do MISTO ou se ela não terminou. */
  public DesfechoInteracao desfechoDados() {
    return desfechoDados.get();
  }

  /** Desfecho da parte de lei do MISTO; nulo fora do MISTO ou se ela não terminou. */
  public DesfechoInteracao desfechoLegislacao() {
    return desfechoLegislacao.get();
  }

  /** Se a parte de lei do MISTO recebeu a pergunta reescrita; nulo se ela não terminou. */
  public Boolean perguntaLeiReescrita() {
    return perguntaLeiReescrita.get();
  }

  /** Funções pedidas pelo modelo nesta pergunta. */
  public int chamadasDeFuncao() {
    return chamadasDeFuncao.get();
  }

  public UUID produtorId() {
    return produtorId;
  }

  public LocalDate hoje() {
    return hoje;
  }

  /** Fontes registradas até agora, na ordem das chamadas. */
  public List<FonteDado> fontes() {
    synchronized (fontes) {
      return List.copyOf(fontes);
    }
  }

  /**
   * Marca a pergunta como interrompida. Só o primeiro motivo vale: um timeout seguido da
   * desconexão do cliente continua registrado como timeout.
   *
   * @param motivo {@link DesfechoInteracao#TEMPO_ESGOTADO} ou {@link DesfechoInteracao#CANCELADA}
   */
  public void interromper(DesfechoInteracao motivo) {
    interrupcao.compareAndSet(null, Objects.requireNonNull(motivo, "motivo"));
  }

  /** Motivo da interrupção, se a pergunta foi interrompida. */
  public Optional<DesfechoInteracao> interrupcao() {
    return Optional.ofNullable(interrupcao.get());
  }

  /**
   * Chamado antes de cada chamada ao modelo e de cada rodada de funções: pergunta interrompida
   * não gasta mais modelo nem banco.
   *
   * @throws PerguntaInterrompidaException se a pergunta foi interrompida
   */
  public void verificarAtiva() {
    if (interrupcao.get() != null) {
      throw new PerguntaInterrompidaException();
    }
  }

  /** Não expõe o produtor: o contexto pode acabar em log por engano. */
  @Override
  public String toString() {
    return "ContextoAssistente[hoje=" + hoje + ", fontes=" + fontes.size() + "]";
  }
}
