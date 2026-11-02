package br.com.agrocontabil.assistente.orquestracao;

import br.com.agrocontabil.assistente.DataSanitizer;
import br.com.agrocontabil.config.AssistenteProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
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
 */
@Component
public class EmbeddingClassificadorIntencao implements DisposableBean {

  /** Conjunto de treino no classpath (a imagem só copia {@code src}). */
  static final String RECURSO_TREINO = "ceres/treino/classificacao-rota.json";

  private static final Logger log = LoggerFactory.getLogger(EmbeddingClassificadorIntencao.class);
  private static final int LOTE = 32;

  /** Potência da similaridade no peso do voto. */
  static final int NITIDEZ = 8;

  private final ObjectProvider<EmbeddingModel> embeddingModel;
  private final AssistenteProperties.Classificacao propriedades;
  private final List<Exemplo> exemplos;
  private final AtomicBoolean aquecendo = new AtomicBoolean();
  private final ExecutorService consultas;
  private final ExecutorService aquecimento;
  private final LongSupplier relogioNanos;
  private volatile float[][] vetores;
  private volatile long pausaAteNanos;
  private volatile boolean emPausa;

  /** Constrói sobre o modelo de embedding (opcional) e o conjunto de treino do classpath. */
  @Autowired
  public EmbeddingClassificadorIntencao(ObjectProvider<EmbeddingModel> embeddingModel,
      AssistenteProperties propriedades, ObjectMapper objectMapper) {
    this(embeddingModel, propriedades.getClassificacao(), carregar(objectMapper));
  }

  EmbeddingClassificadorIntencao(ObjectProvider<EmbeddingModel> embeddingModel,
      AssistenteProperties.Classificacao propriedades, List<Exemplo> exemplos) {
    this(embeddingModel, propriedades, exemplos, System::nanoTime);
  }

  EmbeddingClassificadorIntencao(ObjectProvider<EmbeddingModel> embeddingModel,
      AssistenteProperties.Classificacao propriedades, List<Exemplo> exemplos,
      LongSupplier relogioNanos) {
    this.embeddingModel = embeddingModel;
    this.propriedades = propriedades;
    this.exemplos = List.copyOf(exemplos);
    this.relogioNanos = relogioNanos;
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
    float[][] treino = vetores;
    if (treino == null) {
      aquecerEmSegundoPlanoSeParado();
      return Optional.empty();
    }
    if (emPausa && relogioNanos.getAsLong() - pausaAteNanos < 0) {
      return Optional.empty();
    }
    String mascarada = DataSanitizer.mascarar(pergunta);
    Future<float[]> chamada;
    try {
      chamada = consultas.submit(() -> embeddingModel.getObject().embed(mascarada));
    } catch (RejectedExecutionException e) {
      log.info("Ceres: fila do embedding da classificacao cheia; a rota cai no modelo");
      return Optional.empty();
    }
    float[] consulta;
    try {
      consulta = normalizado(chamada.get(
          propriedades.getTimeoutEmbedding().toMillis(), TimeUnit.MILLISECONDS));
    } catch (TimeoutException e) {
      chamada.cancel(true);
      return pausar("prazo de " + propriedades.getTimeoutEmbedding().toMillis() + " ms vencido");
    } catch (ExecutionException e) {
      return pausar(e.getCause() == null ? "falha" : e.getCause().getClass().getSimpleName());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    }
    emPausa = false;
    return votar(consulta, treino);
  }

  private Optional<ClassificacaoIntencao> pausar(String motivo) {
    pausaAteNanos = relogioNanos.getAsLong() + propriedades.getPausaAposFalha().toNanos();
    emPausa = true;
    log.info("Ceres: embedding da pergunta sem resposta ({}); a rota cai no modelo e a camada"
        + " pausa por {}", motivo, propriedades.getPausaAposFalha());
    return Optional.empty();
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
    List<Vizinho> vizinhos = new ArrayList<>(treino.length);
    for (int i = 0; i < treino.length; i++) {
      vizinhos.add(new Vizinho(exemplos.get(i).rota(), cosseno(consulta, treino[i])));
    }
    vizinhos.sort(Comparator.comparingDouble(Vizinho::similaridade).reversed());
    List<Vizinho> proximos = vizinhos.subList(0, Math.min(propriedades.getVizinhos(),
        vizinhos.size()));
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
    return Optional.of(new ClassificacaoIntencao(vencedora.getKey(),
        vencedora.getValue() / total, null, CamadaClassificacao.EMBEDDING));
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

  /** Um exemplo do treino. */
  record Exemplo(Intencao rota, String pergunta) {}

  private record Vizinho(Intencao rota, double similaridade) {}
}
