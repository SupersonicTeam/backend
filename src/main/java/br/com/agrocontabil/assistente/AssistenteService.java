package br.com.agrocontabil.assistente;

import br.com.agrocontabil.assistente.auditoria.AuditoriaAssistente;
import br.com.agrocontabil.assistente.continuidade.ContinuidadeConversa;
import br.com.agrocontabil.assistente.continuidade.DecisaoContinuidade.Decisao;
import br.com.agrocontabil.assistente.llm.ModeloLinguagem;
import br.com.agrocontabil.assistente.orquestracao.Intencao;
import br.com.agrocontabil.assistente.orquestracao.OrquestradorService;
import br.com.agrocontabil.assistente.orquestracao.ResultadoOrquestracao;
import br.com.agrocontabil.assistente.porta.CotaUsoPorta;
import br.com.agrocontabil.assistente.porta.IdentidadePorta;
import br.com.agrocontabil.common.ApiError;
import br.com.agrocontabil.config.AssistenteProperties;
import br.com.agrocontabil.config.CaminhoCota;
import br.com.agrocontabil.dto.AssistenteChatRequest;
import br.com.agrocontabil.dto.AssistenteChatResponse;
import br.com.agrocontabil.dto.AssistenteUsoResponse;
import br.com.agrocontabil.dto.TurnoConversa;
import br.com.agrocontabil.exception.AssistenteIndisponivelException;
import br.com.agrocontabil.exception.AssistenteOcupadoException;
import br.com.agrocontabil.exception.AssistenteTempoEsgotadoException;
import br.com.agrocontabil.exception.DomainException;
import br.com.agrocontabil.exception.GlobalExceptionHandler;
import br.com.agrocontabil.model.DesfechoInteracao;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Entrada da Ceres, a assistente fiscal (issues #142, #144 e #157; dentro do módulo desde a #141).
 *
 * <p>A ordem importa:
 * <ol>
 *   <li><strong>identidade primeiro</strong> ({@link IdentidadePorta}), na thread da requisição:
 *       sem produtor autenticado a resposta é 401/403 antes de qualquer chamada ao modelo —
 *       inclusive no streaming, antes de o stream abrir. O produtor segue às funções pelo
 *       {@link ContextoAssistente} (issue #143), nunca pelo {@code SecurityContext};</li>
 *   <li><strong>vaga</strong> (bulkhead, issue #157): no máximo uma pergunta em andamento por
 *       produtor e {@code app.assistente.max-conversas-simultaneas} no total, síncronas e em
 *       streaming somadas. Sem vaga, 503 {@code ASSISTENTE_OCUPADO} com {@code Retry-After}, sem
 *       fila;</li>
 *   <li><strong>orquestração</strong> ({@link OrquestradorService}) numa thread do pool próprio
 *       ({@code agro-ceres-}), com tempo total de {@code app.assistente.timeout-pergunta};</li>
 *   <li><strong>auditoria</strong> (issue #144): toda pergunta que chegou à orquestração é
 *       registrada, sem o texto. Se a gravação falhar, a resposta não é entregue (RF020).</li>
 * </ol>
 *
 * <p><strong>Isolamento de recursos:</strong> a Ceres roda num pool de threads próprio, separado
 * das threads do Tomcat e do scheduler, do tamanho do limite de vagas. Nada aqui é transacional (o
 * {@code ArquiteturaAssistenteTest} trava isso): nenhuma conexão do pool de banco fica presa
 * enquanto o modelo gera. O executor <strong>não é bean</strong> de propósito: um {@code Executor}
 * no contexto faria o Boot recuar a autoconfiguração do executor padrão da aplicação (mesma
 * armadilha do {@code SchedulingConfig}, issue #87).
 *
 * <p><strong>Tempo esgotado:</strong> quem espera marca a pergunta como interrompida e interrompe a
 * thread da Ceres — só enquanto ela está na orquestração, nunca durante a auditoria (uma thread
 * interrompida pode perder a conexão do banco). As rotas conferem a marca antes de cada chamada ao
 * modelo e às funções.
 *
 * <p><strong>Streaming (SSE) sem tokens do modelo:</strong> os eventos são {@code rota} (assim que
 * a intenção é decidida), {@code resposta} (o contrato final, <em>já validado</em>), {@code fim} e,
 * em caso de falha, {@code erro} (envelope {@code errors[]}). Transmitir tokens entregaria ao
 * usuário números e citações antes de o {@code ValidadorNumeros} e o {@code ValidadorCitacao} os
 * conferirem — exatamente o que a arquitetura existe para impedir. Antes de cada chamada ao
 * modelo vai um comentário SSE ({@code :ceres}), que os clientes ignoram: é a escrita que percebe o
 * cliente que saiu antes de a chamada gastar (#499).
 *
 * <p><strong>Cancelamento pelo cliente (#499):</strong> a pergunta cancelada pelo cliente (a
 * conexão caiu, sem o prazo ter vencido antes) paga o que gastou: as chamadas ao modelo concluídas
 * e, da interrompida no meio, a entrada estimada, nunca mais que o reservado. Tempo esgotado,
 * modelo indisponível, erro, auditoria não gravada, sem vaga e desligamento estornam a reserva
 * inteira.
 */
@Service
public class AssistenteService implements DisposableBean {

  /** Rota registrada na auditoria quando a falha acontece antes da classificação. */
  static final String ROTA_INDEFINIDA = "INDEFINIDA";

  /** Prefixo das threads da Ceres (aparece em thread dump e no log). */
  static final String PREFIXO_THREADS = "agro-ceres-";

  /**
   * Folga do timeout do container sobre o prazo da pergunta no streaming. O prazo próprio dispara
   * antes e envia o evento {@code erro}; o timeout do container é só a rede de segurança — quando
   * ele dispara, o emissor já está encerrado e não aceita mais eventos (Spring 6.2).
   */
  static final Duration FOLGA_TIMEOUT_SSE = Duration.ofSeconds(10);

  private static final Logger log = LoggerFactory.getLogger(AssistenteService.class);

  /** Fuso de referência do projeto: "hoje" para a Ceres é a data em Brasília. */
  private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

  private final IdentidadePorta identidade;
  private final CotaUsoPorta cota;
  private final OrquestradorService orquestrador;
  private final AuditoriaAssistente auditoria;
  private final ModeloLinguagem modeloLinguagem;
  private final Clock clock;
  private final Duration timeoutPergunta;
  private final Semaphore vagas;
  private final Set<UUID> produtoresComPergunta = ConcurrentHashMap.newKeySet();
  private final ThreadPoolTaskExecutor executor;
  private final ScheduledThreadPoolExecutor prazos;
  private final ContinuidadeConversa continuidade;

  /** Constrói o serviço sem a continuidade da conversa (testes de turno único). */
  public AssistenteService(
      IdentidadePorta identidade,
      CotaUsoPorta cota,
      OrquestradorService orquestrador,
      AuditoriaAssistente auditoria,
      ModeloLinguagem modeloLinguagem,
      Clock clock,
      AssistenteProperties propriedades) {
    this(identidade, cota, orquestrador, auditoria, modeloLinguagem, clock, propriedades,
        ContinuidadeConversa.desligada());
  }

  /** Constrói o serviço e o pool de threads da Ceres. */
  @Autowired
  public AssistenteService(
      IdentidadePorta identidade,
      CotaUsoPorta cota,
      OrquestradorService orquestrador,
      AuditoriaAssistente auditoria,
      ModeloLinguagem modeloLinguagem,
      Clock clock,
      AssistenteProperties propriedades,
      ContinuidadeConversa continuidade) {
    this.continuidade = continuidade;
    this.identidade = identidade;
    this.cota = cota;
    this.orquestrador = orquestrador;
    this.auditoria = auditoria;
    this.modeloLinguagem = modeloLinguagem;
    this.clock = clock;
    this.timeoutPergunta = propriedades.getTimeoutPergunta();
    int maximo = propriedades.getMaxConversasSimultaneas();
    this.vagas = new Semaphore(maximo);
    this.executor = new ThreadPoolTaskExecutor();
    this.executor.setCorePoolSize(maximo);
    this.executor.setMaxPoolSize(maximo);
    // Quem limita é o semáforo; a fila (do mesmo tamanho, nunca passa dele) só absorve o instante
    // em que uma thread devolveu a vaga mas ainda não voltou ao pool. Sem ela, esse instante
    // viraria um 503 espúrio.
    this.executor.setQueueCapacity(maximo);
    this.executor.setThreadNamePrefix(PREFIXO_THREADS);
    this.executor.setWaitForTasksToCompleteOnShutdown(true);
    this.executor.setAwaitTerminationSeconds(30);
    this.executor.initialize();
    // Prazo das perguntas em streaming: uma thread por vaga, porque o envio do evento de erro
    // escreve no socket e um cliente que nao le poderia segurar a thread e atrasar o prazo das
    // outras perguntas. Cancelado ao fim da pergunta e removido da fila na hora, para nao reter o
    // emissor ate o prazo vencer.
    this.prazos = new ScheduledThreadPoolExecutor(maximo, tarefa -> {
      Thread thread = new Thread(tarefa, PREFIXO_THREADS + "prazo");
      thread.setDaemon(true);
      return thread;
    });
    this.prazos.setRemoveOnCancelPolicy(true);
  }

  /**
   * Responde uma pergunta do produtor autenticado, de forma síncrona.
   *
   * @param request pergunta validada
   * @return resposta final
   * @throws br.com.agrocontabil.exception.NaoAutenticadoException sem identidade (401)
   * @throws br.com.agrocontabil.exception.PapelNaoAutorizadoException contador (403)
   * @throws AssistenteOcupadoException sem vaga (503, {@code Retry-After})
   * @throws AssistenteTempoEsgotadoException pergunta além do tempo total (503)
   * @throws AssistenteIndisponivelException modelo fora ou auditoria não gravada (503)
   */
  public AssistenteChatResponse conversar(AssistenteChatRequest request) {
    UUID produtorId = identidade.produtorAutenticado();
    // Cota (#166): reserva o teto na thread da requisicao, antes da vaga e do stream. Sem saldo,
    // 429 LIMITE_USO_ASSISTENTE como erro HTTP comum. Daqui em diante a reserva e acertada ou
    // estornada exatamente uma vez.
    Execucao execucao = preparar(produtorId, request);
    CompletableFuture<AssistenteChatResponse> resultado = new CompletableFuture<>();
    AtomicReference<AssistenteChatResponse> resposta = new AtomicReference<>();
    AtomicReference<Throwable> falha = new AtomicReference<>();
    // A resposta so e entregue depois de a vaga voltar: o cliente pode perguntar de novo assim
    // que recebe, e nao pode levar 503 da propria pergunta anterior.
    try {
      iniciar(produtorId, () -> {
        try {
          resposta.set(responder(execucao, rota -> { }));
        } catch (Throwable t) {
          falha.set(t);
        }
      }, () -> {
        if (falha.get() != null) {
          resultado.completeExceptionally(falha.get());
        } else {
          resultado.complete(resposta.get());
        }
      });
    } catch (RuntimeException e) {
      // Sem vaga: a pergunta nem comecou, a reserva volta inteira.
      encerrarCota(execucao, false);
      throw e;
    }
    try {
      return resultado.get(timeoutPergunta.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      execucao.interromper(DesfechoInteracao.TEMPO_ESGOTADO);
      // Terminou no mesmo instante: a auditoria ja registrou a resposta, entao ela e entregue.
      if (resultado.isDone() && !resultado.isCompletedExceptionally()) {
        return resultado.join();
      }
      log.warn("Pergunta a Ceres passou de {}", timeoutPergunta);
      throw new AssistenteTempoEsgotadoException();
    } catch (InterruptedException e) {
      execucao.interromper(DesfechoInteracao.CANCELADA);
      Thread.currentThread().interrupt();
      throw new AssistenteIndisponivelException(
          "Requisicao interrompida enquanto aguardava a Ceres", e);
    } catch (ExecutionException e) {
      throw relancar(e.getCause());
    }
  }

  /**
   * Responde em streaming (SSE). Identidade e vaga são verificadas aqui, na thread da requisição:
   * falhou, a resposta é o erro HTTP normal (401/403/503), antes de o stream abrir.
   *
   * @param request pergunta validada
   * @param caminho caminho da requisição (vai no envelope do evento {@code erro})
   * @return emissor dos eventos {@code rota}, {@code resposta}, {@code fim} ou {@code erro}
   */
  public SseEmitter conversarEmStream(AssistenteChatRequest request, String caminho) {
    UUID produtorId = identidade.produtorAutenticado();
    // Cota (#166): reserva o teto na thread da requisicao, antes da vaga e do stream. Sem saldo,
    // 429 LIMITE_USO_ASSISTENTE como erro HTTP comum. Daqui em diante a reserva e acertada ou
    // estornada exatamente uma vez.
    Execucao execucao = preparar(produtorId, request);
    SseEmitter emissor = new SseEmitter(timeoutPergunta.plus(FOLGA_TIMEOUT_SSE).toMillis());
    abrirNaThreadDaRequisicao(emissor);
    execucao.contexto.aoChamarOModelo(() -> avisarAntesDaChamada(emissor, execucao));
    // Rede de seguranca: o prazo proprio abaixo dispara antes.
    emissor.onTimeout(() -> execucao.interromper(DesfechoInteracao.TEMPO_ESGOTADO));
    // Cliente foi embora (o container percebeu): para de gastar modelo e banco com ele.
    emissor.onError(falha -> {
      // Sem a classe da falha, um stream que para no meio nao deixa rastro no log (#335).
      log.info("Streaming da Ceres: o container avisou falha na conexao ({})",
          falha == null ? "sem causa" : falha.getClass().getSimpleName());
      // So marca: a cota e encerrada no finally do responder, quando a thread da Ceres para (#499).
      if (desconexao(falha)) {
        execucao.cobrarGasto();
      }
      execucao.interromper(DesfechoInteracao.CANCELADA);
    });
    ScheduledFuture<?> prazo;
    try {
      prazo = prazos.schedule(() -> esgotarPrazo(emissor, execucao, caminho),
          timeoutPergunta.toMillis(), TimeUnit.MILLISECONDS);
    } catch (RejectedExecutionException e) {
      // Aplicacao desligando: nenhuma pergunta nova.
      encerrarCota(execucao, false);
      throw AssistenteOcupadoException.limiteGlobal();
    }
    try {
      // O prazo e cancelado quando a pergunta termina, e nao so no onCompletion do emissor: se a
      // Ceres encerra o stream antes de o Spring assumir o emissor (pergunta rapida, como fora de
      // escopo ou fallback), o Spring nunca registra o onCompletion, e o prazo ficava agendado
      // ate vencer, segurando o emissor e registrando um tempo esgotado falso (#335). Cancelado
      // antes de a vaga voltar: quem pergunta de novo ja nao encontra o prazo da anterior.
      iniciar(produtorId, () -> {
        try {
          transmitir(emissor, execucao, caminho);
        } finally {
          prazo.cancel(false);
        }
      }, () -> { });
    } catch (RuntimeException e) {
      prazo.cancel(false);
      encerrarCota(execucao, false);
      throw e;
    }
    // Cliente que foi embora antes de a pergunta terminar: o prazo nao tem mais a quem avisar.
    emissor.onCompletion(() -> prazo.cancel(false));
    return emissor;
  }

  /**
   * Uso da Ceres na semana do produtor autenticado (issue #166), para a barra de uso do chat. Só
   * créditos: o usuário nunca vê tokens.
   *
   * @throws br.com.agrocontabil.exception.NaoAutenticadoException sem identidade (401)
   * @throws br.com.agrocontabil.exception.PapelNaoAutorizadoException contador (403)
   */
  public AssistenteUsoResponse uso() {
    CotaUsoPorta.UsoCota uso = cota.consultar(identidade.produtorAutenticado());
    return new AssistenteUsoResponse(uso.plano(), uso.habilitada(), uso.creditosSemana(),
        uso.consumidos(), uso.restantes(), uso.renovaEm(),
        continuidade.conversaVaiAoProvedor());
  }

  /**
   * Abre o stream com um comentário SSE ({@code :ceres}), que fica no buffer do emissor até o
   * Spring assumi-lo. O {@code initialize} do Spring escreve esse buffer e faz o flush ainda na
   * thread da requisição: o commit da resposta (status, cabeçalhos e os cabeçalhos de segurança do
   * {@code HeaderWriterFilter}) acontece ali, e não na thread da Ceres (#335).
   *
   * <p>Antes, o primeiro envio da Ceres fazia o commit e rodava os escritores de cabeçalho do
   * Spring Security enquanto a thread da requisição ainda saía do {@code DispatcherServlet}. O
   * escritor da CSP lê o caminho analisado da requisição em dois passos, e o {@code finally} do
   * {@code DispatcherServlet} remove esse atributo: quando a remoção caía entre os dois, o envio
   * da {@code rota} falhava ({@code IllegalArgumentException}) depois de escrever metade do evento,
   * e o stream fechava sem {@code resposta}, {@code fim} nem {@code erro}. Com o commit na thread
   * da requisição, a Ceres só escreve corpo. Clientes SSE ignoram comentário; de brinde, o cliente
   * recebe os cabeçalhos na hora, sem esperar a classificação.
   */
  private static void abrirNaThreadDaRequisicao(SseEmitter emissor) {
    try {
      emissor.send(SseEmitter.event().comment("ceres"));
    } catch (IOException e) {
      // Emissor recem-criado, sem handler: o envio so vai para o buffer e nao faz I/O.
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Prazo da pergunta em streaming vencido. Ordem: marca, envia o erro e encerra o emissor, e só
   * então interrompe a thread. Com a marca de tempo esgotado a thread da Ceres não encerra o
   * emissor (quem encerra é aqui); interrompê-la antes faria o erro chegar a um emissor encerrado.
   */
  private void esgotarPrazo(SseEmitter emissor, Execucao execucao, String caminho) {
    execucao.contexto.interromper(DesfechoInteracao.TEMPO_ESGOTADO);
    log.warn("Pergunta a Ceres (streaming) passou de {}", timeoutPergunta);
    enviarErro(emissor,
        GlobalExceptionHandler.corpoDe(new AssistenteTempoEsgotadoException(), caminho));
    execucao.interromper(DesfechoInteracao.TEMPO_ESGOTADO);
  }

  /**
   * Reserva a vaga do produtor e a global e entrega a tarefa ao pool. As duas vagas são devolvidas
   * no {@code finally} da própria tarefa — só quando a thread realmente terminou —, ou aqui, se o
   * pool recusar. {@code aoLiberar} roda depois de devolvidas.
   */
  private void iniciar(UUID produtorId, Runnable tarefa, Runnable aoLiberar) {
    if (!produtoresComPergunta.add(produtorId)) {
      throw AssistenteOcupadoException.perguntaEmAndamento();
    }
    if (!vagas.tryAcquire()) {
      produtoresComPergunta.remove(produtorId);
      throw AssistenteOcupadoException.limiteGlobal();
    }
    try {
      executor.execute(() -> {
        try {
          tarefa.run();
        } finally {
          liberar(produtorId);
          aoLiberar.run();
        }
      });
    } catch (RejectedExecutionException e) {
      // Pool desligando (shutdown) ou saturado: nada foi executado.
      liberar(produtorId);
      throw AssistenteOcupadoException.limiteGlobal();
    }
  }

  private void liberar(UUID produtorId) {
    produtoresComPergunta.remove(produtorId);
    vagas.release();
  }

  private void transmitir(SseEmitter emissor, Execucao execucao, String caminho) {
    try {
      AssistenteChatResponse resposta = responder(execucao,
          rota -> enviar(emissor, execucao, "rota", Map.of("rota", rota.name())));
      enviar(emissor, execucao, "resposta", resposta);
      enviar(emissor, execucao, "fim", Map.of());
      emissor.complete();
    } catch (RuntimeException e) {
      Optional<DesfechoInteracao> interrupcao = execucao.contexto.interrupcao();
      if (interrupcao.isPresent()) {
        log.info("Streaming da Ceres encerrado por interrupcao: {}", interrupcao.get());
        if (interrupcao.get() != DesfechoInteracao.TEMPO_ESGOTADO) {
          // Cliente desconectado. No tempo esgotado quem envia o erro e encerra e o esgotarPrazo.
          emissor.complete();
        }
      } else if (e instanceof DomainException dominio) {
        if (dominio.getStatus().is5xxServerError()
            && dominio.getStatus() != HttpStatus.SERVICE_UNAVAILABLE) {
          log.error("Erro de dominio no streaming da Ceres: {}", dominio.getMessage(), dominio);
        } else {
          log.warn("Erro no streaming da Ceres: {}", dominio.getMessage());
        }
        enviarErro(emissor, GlobalExceptionHandler.corpoDe(dominio, caminho));
      } else {
        log.error("Falha inesperada no streaming da Ceres: {}", e.getClass().getName(), e);
        enviarErro(emissor, GlobalExceptionHandler.corpoErroInterno(caminho));
      }
    }
  }

  /**
   * Responde e encerra a cota (issue #166): pergunta concluída e não interrompida é acertada pelo
   * consumo real (no modo fallback, sem modelo, o consumo é zero); qualquer falha, timeout ou
   * desconexão estorna a reserva inteira.
   */
  private AssistenteChatResponse responder(Execucao execucao, Consumer<Intencao> aoDecidirRota) {
    boolean concluida = false;
    try {
      AssistenteChatResponse resposta = responderEAuditar(execucao, aoDecidirRota);
      concluida = true;
      return resposta;
    } finally {
      encerrarCota(execucao, concluida);
    }
  }

  /**
   * Reserva da cota (#166) e contexto da pergunta. Atalho fixo da tela (#343) e conversa leve
   * (#493) não chamam o modelo e custam zero crédito: não reservam o teto nem recebem 429
   * {@code LIMITE_USO_ASSISTENTE} com a semana esgotada, e o contexto recusa qualquer chamada ao
   * modelo (#499). A reserva inativa faz o acerto e o estorno não mexerem na cota. O orquestrador
   * e a reserva usam a mesma função estática sobre o mesmo texto.
   */
  private Execucao preparar(UUID produtorId, AssistenteChatRequest request) {
    String pergunta = request.pergunta();
    ContextoAssistente contexto = novoContexto(produtorId);
    // Continuidade (#499): a decisao sai antes da reserva; o mesmo objeto vai ao orquestrador.
    Instant agora = clock.instant();
    Decisao decisao = continuidade.decidir(produtorId, request, agora);
    CotaUsoPorta.ReservaCota reserva;
    if (decisao.custo() == CaminhoCota.SEM_MODELO) {
      contexto.semReservaParaOModelo();
      reserva = new CotaUsoPorta.ReservaCota(produtorId, null, 0, false);
    } else {
      reserva = cota.reservar(produtorId, modeloLinguagem.identificacao(), decisao.custo());
    }
    return new Execucao(contexto, pergunta, reserva, decisao, agora);
  }

  /**
   * Acerto ou estorno da reserva, uma vez só por pergunta. Falha aqui não muda a resposta já
   * auditada: vai ao log, e a reserva presa fica limitada à semana (a linha da próxima é outra).
   *
   * <p>Concluída e não interrompida, acerta pelo consumo. Cancelada pelo cliente e auditada
   * (#499), acerta pelo gasto: as chamadas concluídas e a entrada estimada da interrompida; sem
   * nenhum gasto, estorna. Todo o resto estorna a reserva inteira.
   */
  private void encerrarCota(Execucao execucao, boolean concluida) {
    if (!execucao.cotaEncerrada.compareAndSet(false, true)) {
      return;
    }
    ContextoAssistente contexto = execucao.contexto;
    ContextoAssistente.Gasto gasto = contexto.gasto();
    try {
      if (concluida && contexto.interrupcao().isEmpty()) {
        Integer entrada = contexto.tokensEntrada();
        Integer saida = contexto.tokensSaida();
        cota.acertar(execucao.reserva, entrada == null ? 0 : entrada, saida == null ? 0 : saida,
            modeloLinguagem.identificacao());
      } else if (execucao.canceladaPeloCliente() && !gasto.nada()) {
        log.info("Cota da Ceres: pergunta cancelada pelo cliente, cobrado o gasto");
        cota.acertar(execucao.reserva, gasto.entrada(), gasto.saida(),
            modeloLinguagem.identificacao());
      } else {
        cota.estornar(execucao.reserva);
      }
    } catch (RuntimeException e) {
      log.error("Falha ao encerrar a cota da Ceres: {}", e.getClass().getName());
    }
  }

  private AssistenteChatResponse responderEAuditar(
      Execucao execucao, Consumer<Intencao> aoDecidirRota) {
    ContextoAssistente contexto = execucao.contexto;
    String pergunta = execucao.pergunta;
    long inicio = System.nanoTime();
    AtomicReference<Intencao> rota = new AtomicReference<>();
    ResultadoOrquestracao resultado = null;
    RuntimeException falha = null;
    execucao.entrar();
    try {
      Consumer<Intencao> avisar = decidida -> {
        rota.set(decidida);
        aoDecidirRota.accept(decidida);
      };
      // Flag desligada: a chamada de antes, byte a byte; ligada, com a decisao da continuidade.
      resultado = continuidade.habilitada()
          ? orquestrador.responder(pergunta, contexto, avisar, execucao.decisao, true)
          : orquestrador.responder(pergunta, contexto, avisar);
    } catch (RuntimeException e) {
      falha = e;
    } finally {
      // Daqui em diante a thread nao e mais interrompida: a auditoria precisa da conexao.
      execucao.sair();
    }

    if (falha != null) {
      DesfechoInteracao desfecho = contexto.interrupcao().orElse(
          falha instanceof AssistenteIndisponivelException
              ? DesfechoInteracao.MODELO_INDISPONIVEL
              : DesfechoInteracao.ERRO);
      if (auditarSemFalhar(contexto, rota.get(), desfecho, inicio, pergunta)) {
        execucao.auditada = true;
      }
      throw falha;
    }

    // Interrompida depois de a orquestracao terminar: quem esperava ja respondeu ao cliente.
    Optional<DesfechoInteracao> interrupcao = contexto.interrupcao();
    try {
      auditoria.registrar(contexto, resultado.rota().name(),
          interrupcao.orElse(resultado.desfecho()), modeloLinguagem.identificacao(),
          decorridoMs(inicio), pergunta.length());
      execucao.auditada = true;
    } catch (RuntimeException e) {
      // RF020: sem registro de auditoria, a resposta nao e entregue.
      throw new AssistenteIndisponivelException(
          "Falha ao registrar a auditoria da Ceres: " + e.getClass().getSimpleName(), e);
    }
    if (interrupcao.isPresent()) {
      throw new PerguntaInterrompidaException();
    }

    // Fontes so quando a parte de dados foi aceita: resposta descartada e texto fixo do backend.
    List<AssistenteChatResponse.FonteDado> fontes = resultado.exibirFontes()
        ? contexto.fontes().stream()
            .map(f -> new AssistenteChatResponse.FonteDado(
                f.funcao(), f.parametros(), f.origem()))
            .toList()
        : List.of();
    List<AssistenteChatResponse.Citacao> citacoes = resultado.citacoes().stream()
        .map(c -> new AssistenteChatResponse.Citacao(
            c.norma(), c.dispositivo(), c.url(), c.trecho()))
        .toList();
    // Continuidade (#499): o turno assinado e a pergunta considerada, so com a flag ligada.
    String considerada = resultado.perguntaConsiderada() == null
        ? pergunta : resultado.perguntaConsiderada();
    TurnoConversa turno = emiteTurno(resultado)
        ? continuidade.emitir(contexto.produtorId(), considerada, resultado.texto(),
            resultado.rota(), contexto.registroTurno(), execucao.agora).orElse(null)
        : null;
    String interpretada = continuidade.habilitada() && !considerada.equals(pergunta)
        ? considerada : null;
    return new AssistenteChatResponse(
        resultado.texto(), resultado.rota().name(), citacoes, fontes, resultado.modoFallback(),
        resultado.atalhos().stream().map(Enum::name).toList(), interpretada, turno);
  }

  /**
   * Há {@code turno} (#499) com a rota de dados, de lei ou mista, fora do modo fallback e fora da
   * recusa firme da rota de dados. Conversa leve, recusa e fora de escopo não têm.
   */
  private static boolean emiteTurno(ResultadoOrquestracao resultado) {
    Intencao rota = resultado.rota();
    return (rota == Intencao.DADOS || rota == Intencao.LEGISLACAO || rota == Intencao.MISTO)
        && !resultado.modoFallback() && !PromptsCeres.RECUSA_FIRME.equals(resultado.texto());
  }

  private ContextoAssistente novoContexto(UUID produtorId) {
    return new ContextoAssistente(produtorId, LocalDate.now(clock.withZone(ZONE)));
  }

  /**
   * A falha original é a que importa ao cliente; a da auditoria vai só ao log.
   *
   * @return {@code true} se a auditoria foi gravada
   */
  private boolean auditarSemFalhar(ContextoAssistente contexto, Intencao rota,
      DesfechoInteracao desfecho, long inicio, String pergunta) {
    try {
      auditoria.registrar(contexto, rota == null ? ROTA_INDEFINIDA : rota.name(), desfecho,
          modeloLinguagem.identificacao(), decorridoMs(inicio), pergunta.length());
      return true;
    } catch (RuntimeException e) {
      log.error("Falha ao registrar a auditoria de uma pergunta que falhou: {}",
          e.getClass().getName());
      return false;
    }
  }

  private static void enviar(SseEmitter emissor, Execucao execucao, String evento, Object dado) {
    try {
      emissor.send(SseEmitter.event().name(evento).data(dado, MediaType.APPLICATION_JSON));
    } catch (IOException | IllegalStateException e) {
      // Cliente foi embora (ou o emissor ja foi encerrado): nao adianta seguir gerando. So as
      // classes vao ao log: a mensagem do Spring ("Failed to send ...") traz o dado do evento.
      log.info("Streaming da Ceres: evento {} nao enviado ({}, causa {})", evento,
          e.getClass().getSimpleName(),
          e.getCause() == null ? "nenhuma" : e.getCause().getClass().getSimpleName());
      pararPelaEscrita(execucao, e);
    }
  }

  /**
   * Comentário SSE antes de cada chamada ao modelo (#499): o cliente que saiu é percebido aqui,
   * antes de a chamada gastar, e não só na escrita da {@code resposta}, com tudo já gasto.
   */
  private static void avisarAntesDaChamada(SseEmitter emissor, Execucao execucao) {
    try {
      emissor.send(SseEmitter.event().comment("ceres"));
    } catch (IOException | IllegalStateException e) {
      log.info("Streaming da Ceres: comentario antes da chamada ao modelo nao enviado ({}, causa "
          + "{})", e.getClass().getSimpleName(),
          e.getCause() == null ? "nenhuma" : e.getCause().getClass().getSimpleName());
      pararPelaEscrita(execucao, e);
    }
  }

  /**
   * Escrita recusada: a pergunta para. {@link IOException} é o cliente que saiu (paga o gasto,
   * #499); {@link IllegalStateException} é o emissor já encerrado pelo servidor (prazo vencido),
   * que não é cancelamento do cliente.
   */
  private static void pararPelaEscrita(Execucao execucao, Exception falha) {
    if (falha instanceof IOException) {
      execucao.cobrarGasto();
    }
    execucao.contexto.interromper(DesfechoInteracao.CANCELADA);
    throw new PerguntaInterrompidaException();
  }

  /**
   * Falha avisada pelo container que é a conexão do cliente caindo (#499): uma
   * {@link IOException} na cadeia ({@code ClientAbortException} do Tomcat e
   * {@code AsyncRequestNotUsableException} do Spring são {@code IOException}).
   */
  static boolean desconexao(Throwable falha) {
    Throwable atual = falha;
    for (int i = 0; atual != null && i < 16; i++) {
      if (atual instanceof IOException) {
        return true;
      }
      atual = atual.getCause() == atual ? null : atual.getCause();
    }
    return false;
  }

  private static void enviarErro(SseEmitter emissor, ApiError erro) {
    try {
      emissor.send(SseEmitter.event().name("erro").data(erro, MediaType.APPLICATION_JSON));
      emissor.complete();
    } catch (IOException | IllegalStateException e) {
      emissor.complete();
    }
  }

  private static RuntimeException relancar(Throwable causa) {
    if (causa instanceof RuntimeException runtime) {
      return runtime;
    }
    if (causa instanceof Error erro) {
      throw erro;
    }
    return new IllegalStateException("Falha inesperada na Ceres", causa);
  }

  private static long decorridoMs(long inicioNanos) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - inicioNanos);
  }

  /** Prazos de streaming ainda agendados (para teste: pergunta encerrada não deixa prazo). */
  int prazosAgendados() {
    return prazos.getQueue().size();
  }

  /** Espera as respostas em andamento terminarem (graceful shutdown). */
  @Override
  public void destroy() {
    prazos.shutdownNow();
    executor.shutdown();
  }

  /**
   * Uma pergunta em andamento: o contexto e a thread que a processa. A thread só pode ser
   * interrompida entre {@link #entrar()} e {@link #sair()} — a fase da orquestração —, e o
   * {@code synchronized} impede que a interrupção chegue depois, na auditoria ou em outra tarefa
   * que a mesma thread do pool já esteja executando.
   */
  private static final class Execucao {

    private final ContextoAssistente contexto;
    private final String pergunta;
    /** Decisão da continuidade (#499), tomada antes da reserva. */
    private final Decisao decisao;
    /** Instante único da requisição (conferência do histórico e validade do turno). */
    private final Instant agora;
    private final CotaUsoPorta.ReservaCota reserva;
    /** A reserva da cota é encerrada (acerto ou estorno) uma vez só, pelo primeiro que chegar. */
    private final AtomicBoolean cotaEncerrada = new AtomicBoolean();
    /** O cliente saiu (desconexão, #499): a pergunta paga o que gastou. */
    private final AtomicBoolean cobrarGasto = new AtomicBoolean();
    /** A auditoria da pergunta foi gravada: sem ela, nada é cobrado. */
    private volatile boolean auditada;
    private Thread trabalhador;

    private Execucao(ContextoAssistente contexto, String pergunta,
        CotaUsoPorta.ReservaCota reserva, Decisao decisao, Instant agora) {
      this.contexto = contexto;
      this.pergunta = pergunta;
      this.reserva = reserva;
      this.decisao = decisao;
      this.agora = agora;
    }

    private void cobrarGasto() {
      cobrarGasto.set(true);
    }

    /**
     * Cancelada pelo cliente (#499): a conexão caiu, nenhum prazo venceu antes (o primeiro motivo
     * da interrupção é o cancelamento) e a auditoria foi gravada.
     */
    private boolean canceladaPeloCliente() {
      return cobrarGasto.get() && auditada
          && contexto.interrupcao().filter(DesfechoInteracao.CANCELADA::equals).isPresent();
    }

    /** Interrompida antes de a thread começar: a primeira checagem das rotas já para. */
    private synchronized void entrar() {
      trabalhador = Thread.currentThread();
    }

    private synchronized void sair() {
      trabalhador = null;
      // Limpa uma interrupcao que tenha chegado durante a orquestracao.
      Thread.interrupted();
    }

    private void interromper(DesfechoInteracao motivo) {
      contexto.interromper(motivo);
      synchronized (this) {
        if (trabalhador != null) {
          trabalhador.interrupt();
        }
      }
    }
  }
}
