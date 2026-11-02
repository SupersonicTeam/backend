package br.com.agrocontabil.assistente.orquestracao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.assistente.orquestracao.EmbeddingClassificadorIntencao.Exemplo;
import br.com.agrocontabil.config.AssistenteProperties;
import br.com.agrocontabil.support.EmbeddingModelSimulado;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
}
