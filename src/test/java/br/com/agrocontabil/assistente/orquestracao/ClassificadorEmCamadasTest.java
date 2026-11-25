package br.com.agrocontabil.assistente.orquestracao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.assistente.ContextoAssistente;
import br.com.agrocontabil.assistente.llm.DisponibilidadeModelo;
import br.com.agrocontabil.config.AssistenteProperties;
import br.com.agrocontabil.exception.AssistenteIndisponivelException;
import br.com.agrocontabil.model.EstadoEmbeddingClassificacao;
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
    verify(embeddings, never()).consultar(anyString());
    verify(llm, never()).classificar(anyString(), any());
  }

  @Test
  @DisplayName("#612: nota nomeada pelo numero e dados pela regra, mesmo que as embeddings votem lei")
  void notaNomeadaPeloNumero() {
    String pergunta = "A nota número 3191 está com o CST do ICMS definido?";
    // Na VPS, de 24/11, os vizinhos do treino (CST do IBS, fim do ICMS) davam LEGISLACAO.
    when(embeddings.consultar(anyString())).thenReturn(respondeu(
        new ClassificacaoIntencao(Intencao.LEGISLACAO, 0.9, null, CamadaClassificacao.EMBEDDING)));

    ClassificacaoIntencao c = classificador.classificar(pergunta, contexto);

    assertThat(c.intencao()).isEqualTo(Intencao.DADOS);
    assertThat(c.camada()).isEqualTo(CamadaClassificacao.REGRA);
    verify(embeddings, never()).consultar(anyString());
    verify(llm, never()).classificar(anyString(), any());
  }

  @Test
  @DisplayName("embeddings acima do limiar decidem; abaixo, o modelo desempata")
  void embeddingsEDesempate() {
    when(embeddings.consultar("A soja tem reducao?")).thenReturn(respondeu(
        new ClassificacaoIntencao(Intencao.DADOS, 0.95, null, CamadaClassificacao.EMBEDDING)));
    assertThat(classificador.classificar("A soja tem reducao?", contexto).camada())
        .isEqualTo(CamadaClassificacao.EMBEDDING);
    verify(llm, never()).classificar(anyString(), any());

    when(embeddings.consultar("E o adubo?")).thenReturn(respondeu(
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
    when(embeddings.consultar(pergunta)).thenReturn(respondeu(
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
  @DisplayName("#497: com o modelo no ar o limiar e 0,9; com ele em pausa, o de antes (0,6)")
  void limiarPorDisponibilidade() {
    String pergunta = "E o adubo?";
    when(embeddings.consultar(pergunta)).thenReturn(respondeu(
        new ClassificacaoIntencao(Intencao.DADOS, 0.8, null, CamadaClassificacao.EMBEDDING)));
    when(llm.classificar(pergunta, contexto))
        .thenReturn(new ClassificacaoIntencao(Intencao.MISTO, 0.9, "adubo tem reducao?"));

    assertThat(classificador.classificar(pergunta, contexto).camada())
        .isEqualTo(CamadaClassificacao.LLM);

    when(disponibilidade.disponivel()).thenReturn(false);
    assertThat(classificador.classificar(pergunta, contexto))
        .extracting(ClassificacaoIntencao::intencao, ClassificacaoIntencao::camada)
        .containsExactly(Intencao.DADOS, CamadaClassificacao.EMBEDDING);
  }

  @Test
  @DisplayName("#497: MISTO das embeddings com outra rota do modelo: vale a do modelo")
  void mistoComOutraRotaDoModelo() {
    String pergunta = "Quanto eu devo de Funrural?";
    when(embeddings.consultar(pergunta)).thenReturn(respondeu(
        new ClassificacaoIntencao(Intencao.MISTO, 0.95, null, CamadaClassificacao.EMBEDDING)));
    when(llm.classificar(pergunta, contexto))
        .thenReturn(new ClassificacaoIntencao(Intencao.DADOS, 0.9));

    assertThat(classificador.classificar(pergunta, contexto))
        .extracting(ClassificacaoIntencao::intencao, ClassificacaoIntencao::camada,
            ClassificacaoIntencao::perguntaLei)
        .containsExactly(Intencao.DADOS, CamadaClassificacao.LLM, null);
    verify(llm, times(1)).classificar(pergunta, contexto);
  }

  @Test
  @DisplayName("#497: MISTO das embeddings com o modelo inseguro ou ilegivel: fica o MISTO")
  void mistoComModeloInseguro() {
    String pergunta = "Quanto eu devo de Funrural?";
    when(embeddings.consultar(pergunta)).thenReturn(respondeu(
        new ClassificacaoIntencao(Intencao.MISTO, 0.95, null, CamadaClassificacao.EMBEDDING)));
    when(llm.classificar(pergunta, contexto)).thenReturn(ClassificacaoIntencao.indeterminada());

    assertThat(classificador.classificar(pergunta, contexto))
        .extracting(ClassificacaoIntencao::intencao, ClassificacaoIntencao::camada,
            ClassificacaoIntencao::perguntaLei)
        .containsExactly(Intencao.MISTO, CamadaClassificacao.EMBEDDING, null);
  }

  @Test
  @DisplayName("#497: voto LEGISLACAO com dado do produtor vai ao modelo; sem dado, decide")
  void leiComDadoDoProdutor() {
    String comDado = "As minhas compras de fertilizante dao credito de IBS?";
    String semDado = "Fertilizante da credito de IBS?";
    when(embeddings.consultar(anyString())).thenReturn(respondeu(
        new ClassificacaoIntencao(Intencao.LEGISLACAO, 1, null, CamadaClassificacao.EMBEDDING)));
    when(llm.classificar(comDado, contexto))
        .thenReturn(new ClassificacaoIntencao(Intencao.MISTO, 0.9, "fertilizante da credito?"));

    assertThat(classificador.classificar(comDado, contexto))
        .extracting(ClassificacaoIntencao::intencao, ClassificacaoIntencao::camada)
        .containsExactly(Intencao.MISTO, CamadaClassificacao.LLM);
    assertThat(classificador.classificar(semDado, contexto))
        .extracting(ClassificacaoIntencao::intencao, ClassificacaoIntencao::camada)
        .containsExactly(Intencao.LEGISLACAO, CamadaClassificacao.EMBEDDING);

    // Com o modelo em pausa, o voto das embeddings segue valendo.
    when(disponibilidade.disponivel()).thenReturn(false);
    assertThat(classificador.classificar(comDado, contexto).camada())
        .isEqualTo(CamadaClassificacao.EMBEDDING);
  }

  @Test
  @DisplayName("#497: modelo falha no desempate: vale o voto das embeddings acima de 0,6; abaixo,"
      + " indisponivel")
  void modeloFalhaNoDesempate() {
    when(embeddings.consultar("E o adubo?")).thenReturn(respondeu(
        new ClassificacaoIntencao(Intencao.DADOS, 0.8, null, CamadaClassificacao.EMBEDDING)));
    when(embeddings.consultar("E o calcario?")).thenReturn(respondeu(
        new ClassificacaoIntencao(Intencao.DADOS, 0.5, null, CamadaClassificacao.EMBEDDING)));
    when(llm.classificar(anyString(), any()))
        .thenThrow(new AssistenteIndisponivelException("queda", null));

    assertThat(classificador.classificar("E o adubo?", contexto))
        .extracting(ClassificacaoIntencao::intencao, ClassificacaoIntencao::camada)
        .containsExactly(Intencao.DADOS, CamadaClassificacao.EMBEDDING);
    assertThatThrownBy(() -> classificador.classificar("E o calcario?", contexto))
        .isInstanceOf(AssistenteIndisponivelException.class);
  }

  @Test
  @DisplayName("sem decisao das camadas e modelo em pausa: indisponivel (orquestrador cai no fallback)")
  void semModelo() {
    when(disponibilidade.disponivel()).thenReturn(false);
    when(embeddings.consultar(anyString())).thenReturn(respondeu(null));

    assertThatThrownBy(() -> classificador.classificar("E o adubo?", contexto))
        .isInstanceOf(AssistenteIndisponivelException.class);
    // A regra decide mesmo assim.
    assertThat(classificador.classificar("me fala do split payment", contexto).camada())
        .isEqualTo(CamadaClassificacao.REGRA);
  }

  @Test
  @DisplayName("#497: marca de outra pessoa ou de ordem: o modelo decide, sem regra nem embeddings")
  void marcaDeTerceiroVaiAoModelo() {
    // A regra sozinha diria LEGISLACAO e o vizinho do treino, DADOS; o modelo le o contexto.
    String lei = "Meu vizinho paga Funrural?";
    String dados = "Minhas notas de terceiros ja entraram?";
    when(llm.classificar(lei, contexto))
        .thenReturn(new ClassificacaoIntencao(Intencao.FORA_DE_ESCOPO, 0.9));
    when(llm.classificar(dados, contexto))
        .thenReturn(new ClassificacaoIntencao(Intencao.DADOS, 0.9));

    assertThat(classificador.classificar(lei, contexto))
        .extracting(ClassificacaoIntencao::intencao, ClassificacaoIntencao::camada)
        .containsExactly(Intencao.FORA_DE_ESCOPO, CamadaClassificacao.LLM);
    assertThat(classificador.classificar(dados, contexto))
        .extracting(ClassificacaoIntencao::intencao, ClassificacaoIntencao::camada)
        .containsExactly(Intencao.DADOS, CamadaClassificacao.LLM);
    verify(embeddings, never()).consultar(anyString());
  }

  @Test
  @DisplayName("#497: marca de outra pessoa com o modelo em pausa: as camadas seguem decidindo")
  void marcaDeTerceiroSemModelo() {
    when(disponibilidade.disponivel()).thenReturn(false);

    assertThat(classificador.classificar("Meu vizinho paga Funrural?", contexto))
        .extracting(ClassificacaoIntencao::intencao, ClassificacaoIntencao::camada)
        .containsExactly(Intencao.LEGISLACAO, CamadaClassificacao.REGRA);
    verify(llm, never()).classificar(anyString(), any());
  }

  @Test
  @DisplayName("#497: sem marca, a regra segue decidindo sem o modelo")
  void semMarcaRegraDecide() {
    assertThat(classificador.classificar("Qual a aliquota do IBS do produtor optante?", contexto)
        .camada()).isEqualTo(CamadaClassificacao.REGRA);
    verify(llm, never()).classificar(anyString(), any());
  }

  @Test
  @DisplayName("camadas desligadas: toda pergunta vai ao modelo (Fase 1)")
  void desligado() {
    propriedades.getClassificacao().setCamadas(false);
    when(llm.classificar("me fala do split payment", contexto))
        .thenReturn(new ClassificacaoIntencao(Intencao.LEGISLACAO, 0.9));

    assertThat(classificador.classificar("me fala do split payment", contexto).camada())
        .isEqualTo(CamadaClassificacao.LLM);
    verify(embeddings, never()).consultar(anyString());
  }

  /** Embedding que respondeu no prazo, com o voto (nulo = sem vizinho parecido). */
  private static EmbeddingClassificadorIntencao.Consulta respondeu(ClassificacaoIntencao voto) {
    return new EmbeddingClassificadorIntencao.Consulta(Optional.ofNullable(voto),
        EstadoEmbeddingClassificacao.RESPONDEU, 42);
  }

  @Test
  @DisplayName("#624: o estado e a latencia do embedding vao ao contexto, mesmo com o voto decidindo")
  void estadoDoEmbeddingNoContexto() {
    when(embeddings.consultar("A soja tem reducao?")).thenReturn(respondeu(
        new ClassificacaoIntencao(Intencao.DADOS, 0.95, null, CamadaClassificacao.EMBEDDING)));

    classificador.classificar("A soja tem reducao?", contexto);

    assertThat(contexto.embeddingClassificacao())
        .isEqualTo(EstadoEmbeddingClassificacao.RESPONDEU);
    assertThat(contexto.latenciaEmbeddingClassificacaoMs()).isEqualTo(42);
  }

  @Test
  @DisplayName("#624: com a camada em pausa o modelo decide, e o contexto guarda EM_PAUSA")
  void pausaDeixaRastroNoContexto() {
    when(embeddings.consultar("E o adubo?")).thenReturn(new EmbeddingClassificadorIntencao
        .Consulta(Optional.empty(), EstadoEmbeddingClassificacao.EM_PAUSA, null));
    when(llm.classificar("E o adubo?", contexto))
        .thenReturn(new ClassificacaoIntencao(Intencao.DADOS, 0.9));

    assertThat(classificador.classificar("E o adubo?", contexto).camada())
        .isEqualTo(CamadaClassificacao.LLM);
    assertThat(contexto.embeddingClassificacao())
        .isEqualTo(EstadoEmbeddingClassificacao.EM_PAUSA);
    assertThat(contexto.latenciaEmbeddingClassificacaoMs()).isNull();
  }

  @Test
  @DisplayName("#624: regra, desempate direto ou camadas desligadas: o embedding nao roda e fica nulo")
  void semConsultaSemEstado() {
    classificador.classificar("me fala do split payment", contexto);

    assertThat(contexto.embeddingClassificacao()).isNull();
    assertThat(contexto.latenciaEmbeddingClassificacaoMs()).isNull();
  }
}
