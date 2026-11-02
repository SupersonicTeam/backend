package br.com.agrocontabil.assistente.orquestracao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.assistente.ContextoAssistente;
import br.com.agrocontabil.assistente.llm.DisponibilidadeModelo;
import br.com.agrocontabil.config.AssistenteProperties;
import br.com.agrocontabil.exception.AssistenteIndisponivelException;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Camadas da classificação (issue #265): regra → embeddings → modelo no desempate. */
class ClassificadorEmCamadasTest {

  private LlmClassificadorIntencao llm;
  private EmbeddingClassificadorIntencao embeddings;
  private DisponibilidadeModelo disponibilidade;
  private AssistenteProperties propriedades;
  private ClassificadorEmCamadas classificador;
  private final ContextoAssistente contexto =
      new ContextoAssistente(UUID.randomUUID(), LocalDate.of(2026, 11, 2));

  @BeforeEach
  void setUp() {
    llm = mock(LlmClassificadorIntencao.class);
    embeddings = mock(EmbeddingClassificadorIntencao.class);
    disponibilidade = mock(DisponibilidadeModelo.class);
    propriedades = new AssistenteProperties();
    when(disponibilidade.disponivel()).thenReturn(true);
    classificador = new ClassificadorEmCamadas(llm, embeddings, disponibilidade, propriedades);
  }

  @Test
  @DisplayName("regra decide sem chamar embeddings nem modelo")
  void regra() {
    ClassificacaoIntencao c = classificador.classificar("me fala do split payment", contexto);

    assertThat(c.intencao()).isEqualTo(Intencao.LEGISLACAO);
    assertThat(c.camada()).isEqualTo(CamadaClassificacao.REGRA);
    verify(embeddings, never()).classificar(anyString());
    verify(llm, never()).classificar(anyString(), any());
  }

  @Test
  @DisplayName("embeddings acima do limiar decidem; abaixo, o modelo desempata")
  void embeddingsEDesempate() {
    when(embeddings.classificar("A soja tem reducao?")).thenReturn(Optional.of(
        new ClassificacaoIntencao(Intencao.DADOS, 0.8, null, CamadaClassificacao.EMBEDDING)));
    assertThat(classificador.classificar("A soja tem reducao?", contexto).camada())
        .isEqualTo(CamadaClassificacao.EMBEDDING);
    verify(llm, never()).classificar(anyString(), any());

    when(embeddings.classificar("E o adubo?")).thenReturn(Optional.of(
        new ClassificacaoIntencao(Intencao.DADOS, 0.4, null, CamadaClassificacao.EMBEDDING)));
    when(llm.classificar("E o adubo?", contexto))
        .thenReturn(new ClassificacaoIntencao(Intencao.DADOS, 0.9));
    assertThat(classificador.classificar("E o adubo?", contexto).camada())
        .isEqualTo(CamadaClassificacao.LLM);
  }

  @Test
  @DisplayName("MISTO das embeddings: so a pergunta de lei vem do modelo; sem ele, a inteira")
  void misto() {
    String pergunta = "Quanto vendi de cafe e cafe tem reducao?";
    when(embeddings.classificar(pergunta)).thenReturn(Optional.of(
        new ClassificacaoIntencao(Intencao.MISTO, 0.9, null, CamadaClassificacao.EMBEDDING)));
    when(llm.classificar(pergunta, contexto)).thenReturn(
        new ClassificacaoIntencao(Intencao.MISTO, 0.7, "cafe tem reducao?"));

    ClassificacaoIntencao c = classificador.classificar(pergunta, contexto);
    assertThat(c.intencao()).isEqualTo(Intencao.MISTO);
    assertThat(c.camada()).isEqualTo(CamadaClassificacao.EMBEDDING);
    assertThat(c.perguntaLei()).isEqualTo("cafe tem reducao?");
    assertThat(c.confianca()).isEqualTo(0.9);

    when(disponibilidade.disponivel()).thenReturn(false);
    assertThat(classificador.classificar(pergunta, contexto).perguntaLei()).isNull();
  }

  @Test
  @DisplayName("sem decisao das camadas e modelo em pausa: indisponivel (orquestrador cai no fallback)")
  void semModelo() {
    when(disponibilidade.disponivel()).thenReturn(false);
    when(embeddings.classificar(anyString())).thenReturn(Optional.empty());

    assertThatThrownBy(() -> classificador.classificar("E o adubo?", contexto))
        .isInstanceOf(AssistenteIndisponivelException.class);
    // A regra decide mesmo assim.
    assertThat(classificador.classificar("me fala do split payment", contexto).camada())
        .isEqualTo(CamadaClassificacao.REGRA);
  }

  @Test
  @DisplayName("camadas desligadas: toda pergunta vai ao modelo (Fase 1)")
  void desligado() {
    propriedades.getClassificacao().setCamadas(false);
    when(llm.classificar("me fala do split payment", contexto))
        .thenReturn(new ClassificacaoIntencao(Intencao.LEGISLACAO, 0.9));

    assertThat(classificador.classificar("me fala do split payment", contexto).camada())
        .isEqualTo(CamadaClassificacao.LLM);
    verify(embeddings, never()).classificar(anyString());
  }
}
