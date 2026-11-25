package br.com.agrocontabil.assistente.orquestracao;

import br.com.agrocontabil.assistente.DataSanitizer;
import br.com.agrocontabil.config.AssistenteProperties;
import br.com.agrocontabil.model.EstadoEmbeddingClassificacao;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Segunda camada da classificação (issue #265): a pergunta comparada, por embedding (o BGE-M3 que
 * já serve ao RAG), com o conjunto de treino da equipe ({@value #RECURSO_TREINO}), escrito à parte
 * do gabarito — que é o teste e nunca entra aqui (guarda da #264).
 *
 * <p>kNN com voto ponderado pela similaridade de cosseno: os {@code k} vizinhos mais próximos
 * votam com peso {@code similaridade^}{@value #NITIDEZ}, e a confiança é o peso da rota vencedora
 * sobre o peso total. A potência faz o vizinho próximo dominar: com peso linear, seis vizinhos
 * medianos (palavras comuns) venciam um vizinho quase idêntico. Sem vizinho acima da
 * similaridade mínima, não decide. Os vetores do treino são calculados uma vez, em segundo plano
 * depois da subida ({@link #aquecer()}), e ficam em memória; até lá (ou sem modelo de embedding),
 * a camada não decide e a pergunta segue para o modelo de linguagem — a subida nunca espera o
 * Ollama.
 *
 * <p>Determinístico e sem GPU: uma chamada de embedding por pergunta mais um produto escalar por
 * exemplo. A pergunta vai mascarada pelo {@link DataSanitizer}, como tudo que sai para o modelo.
 *
 * <p><strong>Prazo próprio:</strong> o embedding da pergunta roda num pool pequeno e só é esperado
 * por {@code timeout-embedding} (500 ms); com o Ollama ocupado numa geração, o read-timeout do
 * cliente de IA seguraria a classificação por minutos. Vencido o prazo ou falhada a chamada, a
 * camada não decide e fica em pausa por {@code pausa-apos-falha}, sem chamar o embedding, para não
 * pagar o prazo a cada pergunta enquanto o modelo estiver fora.
 *
 * <p><strong>A pausa deixa rastro (#624):</strong> cada consulta devolve o
 * {@link EstadoEmbeddingClassificacao} (e a espera pelo embedding), que o
 * {@link ClassificadorEmCamadas} põe no contexto da pergunta e a auditoria grava (changeSet 0067);
 * o contador {@value #METRICA_ESTADO} (tag {@code estado}) e o timer {@value #METRICA_LATENCIA}
 * ficam no registro do Micrometer; a pausa e a fila cheia saem em WARN, com o total de pausas desde
 * a subida, e a volta da camada em INFO, com quantas perguntas foram ao modelo durante a pausa. O
 * texto da pergunta nunca vai ao log. A decisão de rota não muda: só fica visível.
 */
@Component
public class EmbeddingClassificadorIntencao implements DisposableBean {

  /** Conjunto de treino no classpath (a imagem só copia {@code src}). */
  static final String RECURSO_TREINO = "ceres/treino/classificacao-rota.json";

  private static final Logger log = LoggerFactory.getLogger(EmbeddingClassificadorIntencao.class);
  private static final int LOTE = 32;

  /** Potência da similaridade no peso do voto. */
  static final int NITIDEZ = 8;

  /** Contador das consultas ao embedding da classificação, por estado (#624). */
  static final String METRICA_ESTADO = "ceres.classificacao.embedding";

  /** Espera pelo embedding da pergunta que voltou no prazo (#624). */
  static final String METRICA_LATENCIA = "ceres.classificacao.embedding.latencia";

  private final ObjectProvider<EmbeddingModel> embeddingModel;
  private final AssistenteProperties.Classificacao propriedades;
  private final List<Exemplo> exemplos;
  private final AtomicBoolean aquecendo = new AtomicBoolean();
  private final ExecutorService consultas;
  private final ExecutorService aquecimento;
  private final LongSupplier relogioNanos;
  private final Map<EstadoEmbeddingClassificacao, Counter> contadores;
  private final Timer latencia;
  /** Pausas desde a subida, para o WARN dizer se é a primeira ou a décima. */
  private final AtomicLong pausas = new AtomicLong();
  /** Perguntas que acharam a camada em pausa desde a última pausa (vão ao INFO da volta). */
  private final AtomicInteger perguntasNaPausa = new AtomicInteger();
  private volatile float[][] vetores;
  private volatile long pausaAteNanos;
  private volatile boolean emPausa;

  /**
   * Constrói sobre o modelo de embedding (opcional), o conjunto de treino do classpath e o registro
   * de métricas (o do Actuator; sem ele, um registro só em memória).
   */
  @Autowired
  public EmbeddingClassificadorIntencao(ObjectProvider<EmbeddingModel> embeddingModel,
      AssistenteProperties propriedades, ObjectMapper objectMapper,
      ObjectProvider<MeterRegistry> registro) {
    this(embeddingModel, propriedades.getClassificacao(), carregar(objectMapper), System::nanoTime,
        registro.getIfAvailable(SimpleMeterRegistry::new));
  }

  EmbeddingClassificadorIntencao(ObjectProvider<EmbeddingModel> embeddingModel,
      AssistenteProperties.Classificacao propriedades, List<Exemplo> exemplos) {
    this(embeddingModel, propriedades, exemplos, System::nanoTime);
  }

  EmbeddingClassificadorIntencao(ObjectProvider<EmbeddingModel> embeddingModel,
      AssistenteProperties.Classificacao propriedades, List<Exemplo> exemplos,
      LongSupplier relogioNanos) {
    this(embeddingModel, propriedades, exemplos, relogioNanos, new SimpleMeterRegistry());
  }

  EmbeddingClassificadorIntencao(ObjectProvider<EmbeddingModel> embeddingModel,
      AssistenteProperties.Classificacao propriedades, List<Exemplo> exemplos,
      LongSupplier relogioNanos, MeterRegistry registro) {
    this.embeddingModel = embeddingModel;
    this.propriedades = propriedades;
    this.exemplos = List.copyOf(exemplos);
    this.relogioNanos = relogioNanos;
    // Todos os estados registrados desde a subida: zero pausas tambem se le no registro.
    Map<EstadoEmbeddingClassificacao, Counter> porEstado =
        new EnumMap<>(EstadoEmbeddingClassificacao.class);
    for (EstadoEmbeddingClassificacao estado : EstadoEmbeddingClassificacao.values()) {
      porEstado.put(estado, Counter.builder(METRICA_ESTADO)
          .description("Consultas ao embedding da classificacao de rota da Ceres, por estado")
          .tag("estado", estado.name())
          .register(registro));
    }
    this.contadores = porEstado;
    this.latencia = Timer.builder(METRICA_LATENCIA)
        .description("Espera pelo embedding da pergunta que voltou no prazo")
        .register(registro);
    // Fila curta: com o embedding preso, a pergunta excedente não espera, vai ao desempate.
    this.consultas = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(8), fabrica("agro-ceres-embedding-"),
        new ThreadPoolExecutor.AbortPolicy());
    this.aquecimento = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(1), fabrica("agro-ceres-treino-"),
        new ThreadPoolExecutor.DiscardPolicy());
  }

  /** Aquece em segundo plano depois da subida, se a classificação em camadas estiver ligada. */
  @EventListener(ApplicationReadyEvent.class)
  public void aquecerEmSegundoPlano() {
    if (!propriedades.isCamadas() || vetores != null
        || !aquecendo.compareAndSet(false, true)) {
      return;
    }
    try {
      aquecimento.execute(() -> {
        try {
          aquecer();
        } finally {
          aquecendo.set(false);
        }
      });
    } catch (RejectedExecutionException e) {
      aquecendo.set(false);
    }
  }

  /**
   * Calcula os vetores do treino, de forma síncrona (o comando de avaliação espera por ele). Uma
   * chamada concorrente espera a primeira terminar. Falha (Ollama fora do ar) deixa a camada
   * desligada até a próxima tentativa, que acontece na próxima pergunta.
   *
   * @return {@code true} se os vetores estão prontos
   */
  public synchronized boolean aquecer() {
    if (vetores != null) {
      return true;
    }
    EmbeddingModel modelo = embeddingModel.getIfAvailable();
    if (modelo == null) {
      return false;
    }
    try {
      float[][] calculados = new float[exemplos.size()][];
      for (int inicio = 0; inicio < exemplos.size(); inicio += LOTE) {
        List<String> textos = exemplos.subList(inicio, Math.min(exemplos.size(), inicio + LOTE))
            .stream().map(Exemplo::pergunta).toList();
        List<float[]> lote = modelo.embed(textos);
        for (int i = 0; i < lote.size(); i++) {
          calculados[inicio + i] = normalizado(lote.get(i));
        }
      }
      vetores = calculados;
      log.info("Ceres: {} exemplos do classificador de rota carregados", exemplos.size());
      return true;
    } catch (RuntimeException e) {
      log.warn("Ceres: treino do classificador sem vetores ({}); a rota cai no modelo",
          e.getClass().getSimpleName());
      return false;
    }
  }

  /**
   * Rota da pergunta pelos vizinhos do treino.
   *
   * @param pergunta pergunta do usuário
   * @return intenção com a confiança do voto; vazio se a camada não está pronta, o embedding
   *     falhou ou nenhum exemplo é parecido o bastante
   */
  public Optional<ClassificacaoIntencao> classificar(String pergunta) {
    return consultar(pergunta).voto();
  }

  /**
   * Rota da pergunta pelos vizinhos do treino, com o que aconteceu com o embedding (#624): o
   * estado vai à auditoria, para a camada {@code LLM} que veio de uma pausa não se confundir com a
   * que veio de um voto fraco.
   *
   * @param pergunta pergunta do usuário
   * @return o voto (vazio fora de {@link EstadoEmbeddingClassificacao#RESPONDEU} ou sem vizinho
   *     parecido), o estado e a espera pelo embedding (nula quando ele não foi chamado)
   */
  public Consulta consultar(String pergunta) {
    float[][] treino = vetores;
    if (treino == null) {
      aquecerEmSegundoPlanoSeParado();
      return semChamada(EstadoEmbeddingClassificacao.SEM_VETORES);
    }
    if (emPausa && relogioNanos.getAsLong() - pausaAteNanos < 0) {
      perguntasNaPausa.incrementAndGet();
      return semChamada(EstadoEmbeddingClassificacao.EM_PAUSA);
    }
    String mascarada = DataSanitizer.mascarar(pergunta);
    long inicio = System.nanoTime();
    Future<float[]> chamada;
    try {
      chamada = consultas.submit(() -> embeddingModel.getObject().embed(mascarada));
    } catch (RejectedExecutionException e) {
      // O comeco da mensagem e o de antes da #624: as regras de medicao contam por ele.
      log.warn("Ceres: fila do embedding da classificacao cheia; a rota cai no modelo (sem pausar"
          + " a camada)");
      return semChamada(EstadoEmbeddingClassificacao.FILA_CHEIA);
    }
    float[] consulta;
    try {
      consulta = normalizado(chamada.get(
          propriedades.getTimeoutEmbedding().toMillis(), TimeUnit.MILLISECONDS));
    } catch (TimeoutException e) {
      chamada.cancel(true);
      return pausar(EstadoEmbeddingClassificacao.PRAZO_VENCIDO, "prazo de "
          + propriedades.getTimeoutEmbedding().toMillis() + " ms vencido", decorridoMs(inicio));
    } catch (ExecutionException e) {
      return pausar(EstadoEmbeddingClassificacao.FALHA,
          e.getCause() == null ? "falha" : e.getCause().getClass().getSimpleName(),
          decorridoMs(inicio));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      chamada.cancel(true);
      return new Consulta(Optional.empty(), contar(EstadoEmbeddingClassificacao.INTERROMPIDA),
          decorridoMs(inicio));
    }
    long esperaNanos = System.nanoTime() - inicio;
    latencia.record(Duration.ofNanos(esperaNanos));
    if (emPausa) {
      emPausa = false;
      log.info("Ceres: camada de embeddings da classificacao de volta depois da pausa; {}"
          + " perguntas foram ao modelo durante ela", perguntasNaPausa.getAndSet(0));
    }
    return new Consulta(votar(consulta, treino), contar(EstadoEmbeddingClassificacao.RESPONDEU),
        emMs(esperaNanos));
  }

  private Consulta pausar(EstadoEmbeddingClassificacao estado, String motivo, int esperaMs) {
    pausaAteNanos = relogioNanos.getAsLong() + propriedades.getPausaAposFalha().toNanos();
    emPausa = true;
    perguntasNaPausa.set(0);
    // O comeco da mensagem e o de antes da #624 ("... sem resposta (prazo de N ms vencido); a rota
    // cai no modelo e a camada pausa por PT30S"): as regras de medicao da #497 contam por ele.
    log.warn("Ceres: embedding da pergunta sem resposta ({}); a rota cai no modelo e a camada pausa"
        + " por {} (espera de {} ms; pausa {} desde a subida)", motivo,
        propriedades.getPausaAposFalha(), esperaMs, pausas.incrementAndGet());
    return new Consulta(Optional.empty(), contar(estado), esperaMs);
  }

  private Consulta semChamada(EstadoEmbeddingClassificacao estado) {
    return new Consulta(Optional.empty(), contar(estado), null);
  }

  private EstadoEmbeddingClassificacao contar(EstadoEmbeddingClassificacao estado) {
    contadores.get(estado).increment();
    return estado;
  }

  private static int decorridoMs(long inicioNanos) {
    return emMs(System.nanoTime() - inicioNanos);
  }

  private static int emMs(long nanos) {
    return (int) Math.min(Integer.MAX_VALUE, TimeUnit.NANOSECONDS.toMillis(nanos));
  }

  @Override
  public void destroy() {
    consultas.shutdownNow();
    aquecimento.shutdownNow();
  }

  private static java.util.concurrent.ThreadFactory fabrica(String prefixo) {
    AtomicInteger contador = new AtomicInteger();
    return tarefa -> {
      Thread thread = new Thread(tarefa, prefixo + contador.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    };
  }

  /** Voto ponderado dos k vizinhos mais próximos. */
  Optional<ClassificacaoIntencao> votar(float[] consulta, float[][] treino) {
    return voto(consulta, treino).map(v -> new ClassificacaoIntencao(v.vencedora(),
        v.confianca(), null, CamadaClassificacao.EMBEDDING));
  }

  /**
   * O voto inteiro: vizinhos, peso de cada rota, confiança da vencedora. Vazio sem vizinho acima
   * da similaridade mínima. Também lido pelo diagnóstico do conjunto de desenvolvimento.
   */
  Optional<Voto> voto(float[] consulta, float[][] treino) {
    List<Vizinho> vizinhos = new ArrayList<>(treino.length);
    for (int i = 0; i < treino.length; i++) {
      vizinhos.add(new Vizinho(i, exemplos.get(i).rota(), cosseno(consulta, treino[i])));
    }
    vizinhos.sort(Comparator.comparingDouble(Vizinho::similaridade).reversed());
    List<Vizinho> proximos = List.copyOf(vizinhos.subList(0,
        Math.min(propriedades.getVizinhos(), vizinhos.size())));
    if (proximos.isEmpty()
        || proximos.get(0).similaridade() < propriedades.getSimilaridadeMinima()) {
      return Optional.empty();
    }
    Map<Intencao, Double> pesos = new EnumMap<>(Intencao.class);
    double total = 0;
    for (Vizinho v : proximos) {
      double peso = Math.pow(Math.max(0, v.similaridade()), NITIDEZ);
      pesos.merge(v.rota(), peso, Double::sum);
      total += peso;
    }
    if (total <= 0) {
      return Optional.empty();
    }
    Map.Entry<Intencao, Double> vencedora = pesos.entrySet().stream()
        .max(Map.Entry.comparingByValue()).orElseThrow();
    return Optional.of(new Voto(proximos, pesos, vencedora.getKey(),
        vencedora.getValue() / total));
  }

  /** Vetores prontos. */
  public boolean pronto() {
    return vetores != null;
  }

  private void aquecerEmSegundoPlanoSeParado() {
    if (embeddingModel.getIfAvailable() != null) {
      aquecerEmSegundoPlano();
    }
  }

  private static float[] normalizado(float[] v) {
    double norma = 0;
    for (float x : v) {
      norma += x * x;
    }
    norma = Math.sqrt(norma);
    float[] n = new float[v.length];
    for (int i = 0; i < v.length; i++) {
      n[i] = norma == 0 ? 0 : (float) (v[i] / norma);
    }
    return n;
  }

  private static double cosseno(float[] a, float[] b) {
    double soma = 0;
    for (int i = 0; i < Math.min(a.length, b.length); i++) {
      soma += a[i] * b[i];
    }
    return soma;
  }

  static List<Exemplo> carregar(ObjectMapper objectMapper) {
    try (InputStream in = new ClassPathResource(RECURSO_TREINO).getInputStream()) {
      JsonNode raiz = objectMapper.readTree(in);
      List<Exemplo> exemplos = new ArrayList<>();
      for (JsonNode e : raiz.path("exemplos")) {
        exemplos.add(new Exemplo(Intencao.valueOf(e.path("rota").asText()),
            e.path("pergunta").asText()));
      }
      if (exemplos.isEmpty()) {
        throw new IllegalStateException("Treino do classificador de rota vazio: " + RECURSO_TREINO);
      }
      return exemplos;
    } catch (IOException e) {
      throw new UncheckedIOException("Treino do classificador de rota ausente: " + RECURSO_TREINO,
          e);
    }
  }

  /** Vetores do treino (nulo até o aquecimento). Só para o diagnóstico. */
  float[][] vetoresDoTreino() {
    return vetores;
  }

  /** Exemplos do treino, na ordem dos vetores. */
  List<Exemplo> exemplos() {
    return exemplos;
  }

  /** Vetor normalizado da pergunta, sem prazo nem pausa. Só para o diagnóstico. */
  float[] vetorDe(String pergunta) {
    return normalizado(embeddingModel.getObject().embed(DataSanitizer.mascarar(pergunta)));
  }

  /**
   * Resultado de uma consulta à camada (#624).
   *
   * @param voto       intenção votada pelos vizinhos; vazio fora de {@code RESPONDEU} ou sem
   *                   vizinho parecido o bastante
   * @param estado     o que aconteceu com o embedding da pergunta
   * @param latenciaMs espera pelo embedding, da submissão à resposta, ao prazo vencido ou à falha;
   *                   nula quando ele não foi chamado
   */
  public record Consulta(Optional<ClassificacaoIntencao> voto, EstadoEmbeddingClassificacao estado,
      Integer latenciaMs) {}

  /** Um exemplo do treino. */
  record Exemplo(Intencao rota, String pergunta) {}

  /** Um vizinho: posição do exemplo no treino, rota e similaridade de cosseno. */
  record Vizinho(int indice, Intencao rota, double similaridade) {}

  /** Voto dos vizinhos: peso por rota e confiança da vencedora (peso dela sobre o total). */
  record Voto(List<Vizinho> vizinhos, Map<Intencao, Double> pesos, Intencao vencedora,
      double confianca) {}
}
