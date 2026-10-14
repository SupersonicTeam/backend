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
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ToolContext;

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

  private final UUID produtorId;
  private final LocalDate hoje;
  private final List<FonteDado> fontes = Collections.synchronizedList(new ArrayList<>());
  private final AtomicInteger tokensEntrada = new AtomicInteger();
  private final AtomicInteger tokensSaida = new AtomicInteger();
  private final AtomicBoolean usoInformado = new AtomicBoolean();
  private final AtomicInteger chamadasDeFuncao = new AtomicInteger();
  private final AtomicInteger consultasComResultado = new AtomicInteger();
  private final List<String> trechosCitados = Collections.synchronizedList(new ArrayList<>());
  private final AtomicReference<DesfechoInteracao> interrupcao = new AtomicReference<>();

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
   * @param funcao     nome da função
   * @param parametros parâmetros efetivamente usados na consulta
   */
  public void registrarFonte(String funcao, Map<String, String> parametros) {
    fontes.add(new FonteDado(funcao, Collections.unmodifiableMap(new LinkedHashMap<>(parametros))));
  }

  /**
   * Soma o uso de tokens de uma resposta do modelo, para a auditoria (issue #144). Provedor que
   * não informa uso é ignorado.
   *
   * @param resposta resposta do modelo
   */
  public void registrarUso(ChatResponse resposta) {
    if (resposta == null || resposta.getMetadata() == null) {
      return;
    }
    Usage uso = resposta.getMetadata().getUsage();
    if (uso == null) {
      return;
    }
    Integer entrada = uso.getPromptTokens();
    Integer saida = uso.getCompletionTokens();
    if (entrada != null && entrada > 0) {
      tokensEntrada.addAndGet(entrada);
      usoInformado.set(true);
    }
    if (saida != null && saida > 0) {
      tokensSaida.addAndGet(saida);
      usoInformado.set(true);
    }
  }

  /**
   * Registra se uma consulta aos dados voltou vazia (sem nota, sem guia, sem compra). No MISTO, a
   * parte de dados com todas as consultas vazias vira frase fixa do backend (issue #170).
   *
   * @param vazio {@code true} se a consulta não encontrou nada
   */
  public void registrarResultado(boolean vazio) {
    if (!vazio) {
      consultasComResultado.incrementAndGet();
    }
  }

  /** {@code true} se nenhuma consulta aos dados encontrou algo (inclusive se nenhuma rodou). */
  public boolean nenhumDadoEncontrado() {
    return consultasComResultado.get() == 0;
  }

  /** Conta as funções que o modelo pediu, inclusive as que falharem (issue #144). */
  public void registrarChamadasDeFuncao(int quantidade) {
    chamadasDeFuncao.addAndGet(quantidade);
  }

  /** Tokens de entrada somados; nulo se o provedor não informou uso. */
  public Integer tokensEntrada() {
    return usoInformado.get() ? tokensEntrada.get() : null;
  }

  /** Tokens de saída somados; nulo se o provedor não informou uso. */
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
