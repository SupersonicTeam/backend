package br.com.agrocontabil.assistente.orquestracao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.assistente.orquestracao.EmbeddingClassificadorIntencao.Exemplo;
import br.com.agrocontabil.config.AssistenteProperties;
import br.com.agrocontabil.model.EstadoEmbeddingClassificacao;
import br.com.agrocontabil.support.EmbeddingModelSimulado;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** kNN sobre o treino (issue #265), com o embedding simulado (saco de palavras). */
class EmbeddingClassificadorIntencaoTest {

  private static final List<Exemplo> TREINO = List.of(
      new Exemplo(Intencao.DADOS, "quanto vendi de cafe no mes"),
      new Exemplo(Intencao.DADOS, "quanto vendi de milho no ano"),
      new Exemplo(Intencao.DADOS, "quanto vendi de trigo na safra"),
      new Exemplo(Intencao.LEGISLACAO, "o que diz a lei sobre o fato gerador"),
      new Exemplo(Intencao.LEGISLACAO, "o que diz a lei sobre a base de calculo"),
      new Exemplo(Intencao.FORA_DE_ESCOPO, "receita de bolo de chocolate"));

  private static AssistenteProperties.Classificacao propriedades() {
    AssistenteProperties.Classificacao p = new AssistenteProperties.Classificacao();
    p.setVizinhos(3);
    p.setSimilaridadeMinima(0.3);
    return p;
  }

  private static ObjectProvider<EmbeddingModel> com(EmbeddingModel modelo) {
    StaticListableBeanFactory beans = new StaticListableBeanFactory();
    if (modelo != null) {
      beans.addBean("embedding", modelo);
    }
    return beans.getBeanProvider(EmbeddingModel.class);
  }

  @Test
  @DisplayName("vizinhos votam pela similaridade; confianca = peso da vencedora / peso total")
  void votoPonderado() {
    EmbeddingClassificadorIntencao knn = new EmbeddingClassificadorIntencao(
        com(new EmbeddingModelSimulado()), propriedades(), TREINO);
    assertThat(knn.aquecer()).isTrue();

    ClassificacaoIntencao c = knn.classificar("quanto vendi de soja no mes").orElseThrow();
    assertThat(c.intencao()).isEqualTo(Intencao.DADOS);
    assertThat(c.camada()).isEqualTo(CamadaClassificacao.EMBEDDING);
    assertThat(c.confianca()).isEqualTo(1.0);

    assertThat(knn.classificar("o que diz a lei sobre o split").orElseThrow().intencao())
        .isEqualTo(Intencao.LEGISLACAO);
  }

  @Test
  @DisplayName("nada parecido o bastante, camada nao aquecida ou sem modelo: nao decide")
  void naoDecide() {
    EmbeddingClassificadorIntencao knn = new EmbeddingClassificadorIntencao(
        com(new EmbeddingModelSimulado()), propriedades(), TREINO);
    assertThat(knn.pronto()).isFalse();
    assertThat(knn.classificar("quanto vendi de soja no mes")).isEmpty();

    knn.aquecer();
    assertThat(knn.classificar("xyzzy plugh")).isEmpty();

    EmbeddingClassificadorIntencao semModelo =
        new EmbeddingClassificadorIntencao(com(null), propriedades(), TREINO);
    assertThat(semModelo.aquecer()).isFalse();
    assertThat(semModelo.classificar("quanto vendi")).isEmpty();
  }

  @Test
  @DisplayName("embedding que falha na pergunta ou no aquecimento: nao decide, nunca lanca")
  void falhaDoModelo() {
    EmbeddingModel quebrado = mock(EmbeddingModel.class);
    when(quebrado.embed(org.mockito.ArgumentMatchers.<List<String>>any()))
        .thenThrow(new IllegalStateException("ollama fora"));
    when(quebrado.embed(anyString())).thenThrow(new IllegalStateException("ollama fora"));
    EmbeddingClassificadorIntencao knn =
        new EmbeddingClassificadorIntencao(com(quebrado), propriedades(), TREINO);

    assertThat(knn.aquecer()).isFalse();
    assertThat(knn.classificar("quanto vendi")).isEmpty();
  }

  @Test
  @DisplayName("embedding lento: prazo curto vence, nao decide e pausa; passada a pausa, tenta de novo")
  void prazoEPausa() {
    java.util.concurrent.atomic.AtomicBoolean lento = new java.util.concurrent.atomic.AtomicBoolean();
    java.util.concurrent.atomic.AtomicInteger chamadas =
        new java.util.concurrent.atomic.AtomicInteger();
    EmbeddingModel modelo = new EmbeddingModelSimulado() {
      @Override
      public float[] embed(String texto) {
        chamadas.incrementAndGet();
        if (lento.get()) {
          try {
            Thread.sleep(5_000);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        }
        return EmbeddingModelSimulado.vetor(texto);
      }
    };
    AssistenteProperties.Classificacao p = propriedades();
    p.setTimeoutEmbedding(java.time.Duration.ofMillis(100));
    p.setPausaAposFalha(java.time.Duration.ofSeconds(30));
    java.util.concurrent.atomic.AtomicLong agora = new java.util.concurrent.atomic.AtomicLong();
    EmbeddingClassificadorIntencao knn =
        new EmbeddingClassificadorIntencao(com(modelo), p, TREINO, agora::get);
    try {
      assertThat(knn.aquecer()).isTrue();

      lento.set(true);
      long inicio = System.nanoTime();
      assertThat(knn.classificar("quanto vendi de soja no mes")).isEmpty();
      assertThat(java.time.Duration.ofNanos(System.nanoTime() - inicio))
          .isLessThan(java.time.Duration.ofSeconds(2));
      assertThat(chamadas.get()).isEqualTo(1);

      // Em pausa: nem chama o embedding.
      lento.set(false);
      assertThat(knn.classificar("quanto vendi de soja no mes")).isEmpty();
      assertThat(chamadas.get()).isEqualTo(1);

      agora.addAndGet(java.time.Duration.ofSeconds(31).toNanos());
      assertThat(knn.classificar("quanto vendi de soja no mes")).isPresent();
      assertThat(chamadas.get()).isEqualTo(2);
    } finally {
      knn.destroy();
    }
  }

  @Test
  @DisplayName("o treino do classpath carrega, com as quatro rotas e centenas de exemplos")
  void treinoDoClasspath() {
    List<Exemplo> exemplos = EmbeddingClassificadorIntencao.carregar(new ObjectMapper());

    assertThat(exemplos).hasSizeGreaterThan(200);
    assertThat(exemplos).extracting(Exemplo::rota).contains(Intencao.DADOS,
        Intencao.LEGISLACAO, Intencao.MISTO, Intencao.FORA_DE_ESCOPO);
  }

  // ---------------------------------------------------------------------------------------------
  // #624: a pausa das embeddings fica visivel (estado por pergunta, contador, WARN).
  // ---------------------------------------------------------------------------------------------

  /** Modelo que fica lento ou quebrado sob comando e conta as chamadas da pergunta. */
  private static final class ModeloControlavel extends EmbeddingModelSimulado {
    final AtomicBoolean lento = new AtomicBoolean();
    final AtomicBoolean quebrado = new AtomicBoolean();
    final AtomicInteger chamadas = new AtomicInteger();

    @Override
    public float[] embed(String texto) {
      chamadas.incrementAndGet();
      if (quebrado.get()) {
        throw new IllegalStateException("ollama fora");
      }
      if (lento.get()) {
        try {
          Thread.sleep(5_000);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      return EmbeddingModelSimulado.vetor(texto);
    }
  }

  private static double contagem(MeterRegistry registro, EstadoEmbeddingClassificacao estado) {
    return registro.get(EmbeddingClassificadorIntencao.METRICA_ESTADO)
        .tag("estado", estado.name()).counter().count();
  }

  private static ListAppender<ILoggingEvent> capturarLog() {
    Logger logger = (Logger) LoggerFactory.getLogger(EmbeddingClassificadorIntencao.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    return appender;
  }

  private static void soltarLog(ListAppender<ILoggingEvent> appender) {
    ((Logger) LoggerFactory.getLogger(EmbeddingClassificadorIntencao.class))
        .detachAppender(appender);
  }

  @Test
  @DisplayName("#624: embedding no prazo = RESPONDEU, com a latencia medida e o contador do estado")
  void consultaRespondeu() {
    MeterRegistry registro = new SimpleMeterRegistry();
    EmbeddingClassificadorIntencao knn = new EmbeddingClassificadorIntencao(
        com(new EmbeddingModelSimulado()), propriedades(), TREINO, System::nanoTime, registro);
    try {
      knn.aquecer();

      EmbeddingClassificadorIntencao.Consulta c = knn.consultar("quanto vendi de soja no mes");

      assertThat(c.estado()).isEqualTo(EstadoEmbeddingClassificacao.RESPONDEU);
      assertThat(c.latenciaMs()).isNotNull().isGreaterThanOrEqualTo(0);
      assertThat(c.voto()).isPresent();
      assertThat(contagem(registro, EstadoEmbeddingClassificacao.RESPONDEU)).isEqualTo(1);
      // Os outros estados existem desde a subida, com zero: a ausencia de pausa tambem se le.
      assertThat(contagem(registro, EstadoEmbeddingClassificacao.EM_PAUSA)).isZero();
      assertThat(registro.get(EmbeddingClassificadorIntencao.METRICA_LATENCIA).timer().count())
          .isEqualTo(1);

      // Respondeu, mas sem vizinho parecido: o estado e o mesmo, so o voto falta.
      EmbeddingClassificadorIntencao.Consulta semVizinho = knn.consultar("xyzzy plugh");
      assertThat(semVizinho.estado()).isEqualTo(EstadoEmbeddingClassificacao.RESPONDEU);
      assertThat(semVizinho.voto()).isEmpty();
    } finally {
      knn.destroy();
    }
  }

  @Test
  @DisplayName("#624: sem vetores do treino, SEM_VETORES, sem latencia e sem chamar o embedding")
  void consultaSemVetores() {
    ModeloControlavel modelo = new ModeloControlavel();
    MeterRegistry registro = new SimpleMeterRegistry();
    EmbeddingClassificadorIntencao knn = new EmbeddingClassificadorIntencao(
        com(modelo), propriedades(), TREINO, System::nanoTime, registro);
    try {
      EmbeddingClassificadorIntencao.Consulta c = knn.consultar("quanto vendi");

      assertThat(c.estado()).isEqualTo(EstadoEmbeddingClassificacao.SEM_VETORES);
      assertThat(c.latenciaMs()).isNull();
      assertThat(c.voto()).isEmpty();
      assertThat(modelo.chamadas.get()).isZero();
      assertThat(contagem(registro, EstadoEmbeddingClassificacao.SEM_VETORES)).isEqualTo(1);
    } finally {
      knn.destroy();
    }
  }

  @Test
  @DisplayName("#624: prazo vencido = PRAZO_VENCIDO com WARN; a pausa = EM_PAUSA; a volta e INFO")
  void prazoVencidoEPausaVisiveis() {
    ModeloControlavel modelo = new ModeloControlavel();
    AssistenteProperties.Classificacao p = propriedades();
    p.setTimeoutEmbedding(Duration.ofMillis(100));
    p.setPausaAposFalha(Duration.ofSeconds(30));
    AtomicLong agora = new AtomicLong();
    MeterRegistry registro = new SimpleMeterRegistry();
    EmbeddingClassificadorIntencao knn = new EmbeddingClassificadorIntencao(
        com(modelo), p, TREINO, agora::get, registro);
    ListAppender<ILoggingEvent> log = capturarLog();
    try {
      knn.aquecer();
      modelo.lento.set(true);

      EmbeddingClassificadorIntencao.Consulta vencida = knn.consultar("quanto vendi de soja");
      assertThat(vencida.estado()).isEqualTo(EstadoEmbeddingClassificacao.PRAZO_VENCIDO);
      assertThat(vencida.latenciaMs()).isNotNull().isGreaterThanOrEqualTo(100);
      assertThat(vencida.voto()).isEmpty();
      assertThat(log.list).anySatisfy(e -> {
        assertThat(e.getLevel()).isEqualTo(Level.WARN);
        assertThat(e.getFormattedMessage()).contains("prazo de 100 ms vencido")
            .contains("pausa 1 desde a subida").doesNotContain("soja");
      });

      modelo.lento.set(false);
      EmbeddingClassificadorIntencao.Consulta emPausa = knn.consultar("quanto vendi de milho");
      assertThat(emPausa.estado()).isEqualTo(EstadoEmbeddingClassificacao.EM_PAUSA);
      assertThat(emPausa.latenciaMs()).isNull();
      knn.consultar("quanto vendi de trigo");
      assertThat(modelo.chamadas.get()).isEqualTo(1);
      assertThat(contagem(registro, EstadoEmbeddingClassificacao.PRAZO_VENCIDO)).isEqualTo(1);
      assertThat(contagem(registro, EstadoEmbeddingClassificacao.EM_PAUSA)).isEqualTo(2);

      agora.addAndGet(Duration.ofSeconds(31).toNanos());
      assertThat(knn.consultar("quanto vendi de soja no mes").estado())
          .isEqualTo(EstadoEmbeddingClassificacao.RESPONDEU);
      assertThat(log.list).anySatisfy(e -> {
        assertThat(e.getLevel()).isEqualTo(Level.INFO);
        assertThat(e.getFormattedMessage()).contains("de volta")
            .contains("2 perguntas foram ao modelo");
      });
    } finally {
      soltarLog(log);
      knn.destroy();
    }
  }

  @Test
  @DisplayName("#624: pausas seguidas sao um episodio so; a volta conta as perguntas de todas")
  void pausasSeguidasSomam() {
    ModeloControlavel modelo = new ModeloControlavel();
    AssistenteProperties.Classificacao p = propriedades();
    p.setTimeoutEmbedding(Duration.ofMillis(50));
    p.setPausaAposFalha(Duration.ofSeconds(30));
    AtomicLong agora = new AtomicLong();
    EmbeddingClassificadorIntencao knn = new EmbeddingClassificadorIntencao(
        com(modelo), p, TREINO, agora::get, new SimpleMeterRegistry());
    ListAppender<ILoggingEvent> log = capturarLog();
    try {
      knn.aquecer();
      modelo.lento.set(true);
      knn.consultar("quanto vendi de soja");
      knn.consultar("quanto vendi de milho");
      agora.addAndGet(Duration.ofSeconds(31).toNanos());
      // A sondagem depois da pausa vence o prazo de novo: mesmo episodio.
      assertThat(knn.consultar("quanto vendi de trigo").estado())
          .isEqualTo(EstadoEmbeddingClassificacao.PRAZO_VENCIDO);
      knn.consultar("quanto vendi de cafe");
      knn.consultar("quanto vendi de cafe");
      modelo.lento.set(false);
      agora.addAndGet(Duration.ofSeconds(31).toNanos());
      assertThat(knn.consultar("quanto vendi de soja no mes").estado())
          .isEqualTo(EstadoEmbeddingClassificacao.RESPONDEU);
      knn.consultar("quanto vendi de soja no mes");

      assertThat(log.list).filteredOn(e -> e.getFormattedMessage().contains("de volta"))
          .singleElement()
          .satisfies(e -> assertThat(e.getFormattedMessage())
              .contains("3 perguntas foram ao modelo"));
      assertThat(log.list).filteredOn(e -> e.getLevel() == Level.WARN)
          .extracting(ILoggingEvent::getFormattedMessage)
          .anySatisfy(m -> assertThat(m).contains("pausa 2 desde a subida"));
    } finally {
      soltarLog(log);
      knn.destroy();
    }
  }

  @Test
  @DisplayName("#624: falha do embedding = FALHA com WARN, e a camada pausa do mesmo jeito")
  void falhaVisivel() {
    ModeloControlavel modelo = new ModeloControlavel();
    MeterRegistry registro = new SimpleMeterRegistry();
    EmbeddingClassificadorIntencao knn = new EmbeddingClassificadorIntencao(
        com(modelo), propriedades(), TREINO, () -> 0L, registro);
    ListAppender<ILoggingEvent> log = capturarLog();
    try {
      knn.aquecer();
      modelo.quebrado.set(true);

      EmbeddingClassificadorIntencao.Consulta c = knn.consultar("quanto vendi");

      assertThat(c.estado()).isEqualTo(EstadoEmbeddingClassificacao.FALHA);
      assertThat(c.latenciaMs()).isNotNull();
      assertThat(log.list).anySatisfy(e -> {
        assertThat(e.getLevel()).isEqualTo(Level.WARN);
        assertThat(e.getFormattedMessage()).contains("IllegalStateException");
      });
      assertThat(knn.consultar("quanto vendi").estado())
          .isEqualTo(EstadoEmbeddingClassificacao.EM_PAUSA);
      assertThat(contagem(registro, EstadoEmbeddingClassificacao.FALHA)).isEqualTo(1);
    } finally {
      soltarLog(log);
      knn.destroy();
    }
  }

  @Test
  @DisplayName("#624: com o pool e a fila tomados, FILA_CHEIA com WARN, sem esperar o prazo")
  void filaCheiaVisivel() throws Exception {
    CountDownLatch solta = new CountDownLatch(1);
    EmbeddingModel preso = new EmbeddingModelSimulado() {
      @Override
      public float[] embed(String texto) {
        boolean interrompida = false;
        while (true) {
          try {
            solta.await();
            break;
          } catch (InterruptedException e) {
            interrompida = true;
          }
        }
        if (interrompida) {
          Thread.currentThread().interrupt();
        }
        return EmbeddingModelSimulado.vetor(texto);
      }
    };
    AssistenteProperties.Classificacao p = propriedades();
    p.setTimeoutEmbedding(Duration.ofSeconds(10));
    MeterRegistry registro = new SimpleMeterRegistry();
    EmbeddingClassificadorIntencao knn = new EmbeddingClassificadorIntencao(
        com(preso), p, TREINO, () -> 0L, registro);
    ListAppender<ILoggingEvent> log = capturarLog();
    // Onze perguntas ao mesmo tempo e o modelo preso: duas em curso e oito na fila tomam o pool,
    // e a decima primeira e recusada na hora. So ela termina antes de o modelo soltar.
    ExecutorService perguntas = Executors.newFixedThreadPool(11);
    try {
      // O aquecimento usa o embed em lote, que o modelo preso nao segura.
      assertThat(knn.aquecer()).isTrue();
      List<Future<EmbeddingClassificadorIntencao.Consulta>> todas = new ArrayList<>();
      for (int i = 0; i < 11; i++) {
        todas.add(perguntas.submit(() -> knn.consultar("quanto vendi")));
      }
      long limite = System.nanoTime() + Duration.ofSeconds(5).toNanos();
      while (todas.stream().noneMatch(Future::isDone) && System.nanoTime() < limite) {
        Thread.sleep(10);
      }
      List<Future<EmbeddingClassificadorIntencao.Consulta>> prontas =
          todas.stream().filter(Future::isDone).toList();
      assertThat(prontas).hasSize(1);
      assertThat(prontas.get(0).get().estado()).isEqualTo(EstadoEmbeddingClassificacao.FILA_CHEIA);
      assertThat(prontas.get(0).get().latenciaMs()).isNull();
      assertThat(contagem(registro, EstadoEmbeddingClassificacao.FILA_CHEIA)).isEqualTo(1);
      assertThat(log.list).anySatisfy(e -> {
        assertThat(e.getLevel()).isEqualTo(Level.WARN);
        assertThat(e.getFormattedMessage()).contains("fila do embedding");
      });
      solta.countDown();
      for (Future<EmbeddingClassificadorIntencao.Consulta> f : todas) {
        if (f != prontas.get(0)) {
          assertThat(f.get(10, TimeUnit.SECONDS).estado())
              .isEqualTo(EstadoEmbeddingClassificacao.RESPONDEU);
        }
      }
    } finally {
      solta.countDown();
      perguntas.shutdownNow();
      soltarLog(log);
      knn.destroy();
    }
  }
}
