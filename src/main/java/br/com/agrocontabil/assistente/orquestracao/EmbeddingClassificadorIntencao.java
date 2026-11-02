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
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
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
 */
@Component
public class EmbeddingClassificadorIntencao {

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
  private volatile float[][] vetores;

  /** Constrói sobre o modelo de embedding (opcional) e o conjunto de treino do classpath. */
  @Autowired
  public EmbeddingClassificadorIntencao(ObjectProvider<EmbeddingModel> embeddingModel,
      AssistenteProperties propriedades, ObjectMapper objectMapper) {
    this(embeddingModel, propriedades.getClassificacao(), carregar(objectMapper));
  }

  EmbeddingClassificadorIntencao(ObjectProvider<EmbeddingModel> embeddingModel,
      AssistenteProperties.Classificacao propriedades, List<Exemplo> exemplos) {
    this.embeddingModel = embeddingModel;
    this.propriedades = propriedades;
    this.exemplos = List.copyOf(exemplos);
  }

  /** Aquece em segundo plano depois da subida, se a classificação em camadas estiver ligada. */
  @EventListener(ApplicationReadyEvent.class)
  public void aquecerEmSegundoPlano() {
    if (!propriedades.isCamadas()) {
      return;
    }
    Thread thread = new Thread(this::aquecer, "agro-ceres-treino");
    thread.setDaemon(true);
    thread.start();
  }

  /**
   * Calcula os vetores do treino. Falha (Ollama fora do ar) deixa a camada desligada até a
   * próxima tentativa, que acontece na próxima pergunta.
   *
   * @return {@code true} se os vetores estão prontos
   */
  public boolean aquecer() {
    if (vetores != null) {
      return true;
    }
    EmbeddingModel modelo = embeddingModel.getIfAvailable();
    if (modelo == null || !aquecendo.compareAndSet(false, true)) {
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
    } finally {
      aquecendo.set(false);
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
    float[] consulta;
    try {
      consulta = normalizado(embeddingModel.getObject().embed(DataSanitizer.mascarar(pergunta)));
    } catch (RuntimeException e) {
      log.info("Ceres: embedding da pergunta falhou ({}); a rota cai no modelo",
          e.getClass().getSimpleName());
      return Optional.empty();
    }
    return votar(consulta, treino);
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
    if (propriedades.isCamadas() && !aquecendo.get() && embeddingModel.getIfAvailable() != null) {
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
