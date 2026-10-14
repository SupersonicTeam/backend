package br.com.agrocontabil.assistente.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.exception.AssistenteIndisponivelException;
import br.com.agrocontabil.support.ChatModelSimulado;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.ResourceAccessException;

/** Testes do gateway {@link ModeloLinguagem} (issue #141) e do disjuntor (issue #146). */
class ModeloLinguagemTest {

  private static final Duration PAUSA = Duration.ofSeconds(30);

  private final AtomicLong agora = new AtomicLong(1_000L);
  private ChatModelSimulado chatModel;
  private DisponibilidadeModelo disponibilidade;
  private ModeloLinguagem modelo;

  @BeforeEach
  void setUp() {
    chatModel = new ChatModelSimulado();
    disponibilidade = new DisponibilidadeModelo(PAUSA, agora::get);
    modelo = gatewayCom(chatModel, disponibilidade);
  }

  @Test
  @DisplayName("Repassa o prompt ao modelo e devolve a resposta")
  void repassaPromptEDevolveResposta() {
    chatModel.responderTexto("ola");

    ChatResponse resposta = modelo.chamar(new Prompt("pergunta"));

    assertThat(resposta.getResult().getOutput().getText()).isEqualTo("ola");
    assertThat(chatModel.promptsRecebidos()).hasSize(1);
  }

  @Test
  @DisplayName("Ollama fora do ar (conexao recusada) vira 503 ASSISTENTE_INDISPONIVEL")
  void conexaoRecusadaVira503() {
    chatModel.falharCom(new ResourceAccessException("Connection refused: localhost/127.0.0.1:11434"));

    assertThatThrownBy(() -> modelo.chamar(new Prompt("pergunta")))
        .isInstanceOfSatisfying(AssistenteIndisponivelException.class, e -> {
          assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
          assertThat(e.getErrors()).singleElement().satisfies(detalhe -> {
            assertThat(detalhe.code()).isEqualTo(ErrorCode.ASSISTENTE_INDISPONIVEL.code());
            // Nada do erro tecnico chega ao cliente: nem host, nem porta, nem provedor.
            assertThat(detalhe.message()).doesNotContain("11434", "localhost", "ollama");
          });
          assertThat(e.getCause()).isInstanceOf(ResourceAccessException.class);
        });
  }

  @Test
  @DisplayName("Erros transitorio e permanente do Spring AI viram 503")
  void errosDoSpringAiViram503() {
    for (RuntimeException falha : List.of(
        new TransientAiException("HTTP 503 do provedor"),
        new NonTransientAiException("HTTP 401 - Incorrect API key provided: sk-abc***"))) {
      chatModel.falharCom(falha);

      assertThatThrownBy(() -> modelo.chamar(new Prompt("pergunta")))
          .isInstanceOf(AssistenteIndisponivelException.class)
          // A mensagem tecnica (que vai ao log) leva so o tipo da falha, nunca a mensagem do
          // provedor — ela pode ecoar a requisicao ou um pedaco da chave.
          .satisfies(e -> assertThat(e.getMessage()).doesNotContain("sk-abc", "HTTP"));
    }
  }

  @Test
  @DisplayName("Resposta vazia do modelo vira 503, nao NullPointerException adiante")
  void respostaVaziaVira503() {
    chatModel.responderCom(prompt -> new ChatResponse(List.of()));

    assertThatThrownBy(() -> modelo.chamar(new Prompt("pergunta")))
        .isInstanceOf(AssistenteIndisponivelException.class);
  }

  @Test
  @DisplayName("Sem ChatModel configurado (spring.ai.model.chat=none) responde 503")
  void semModeloConfiguradoResponde503() {
    ModeloLinguagem semModelo = new ModeloLinguagem(
        new StaticListableBeanFactory().getBeanProvider(ChatModel.class), "none",
        disponibilidade);

    assertThatThrownBy(() -> semModelo.chamar(new Prompt("pergunta")))
        .isInstanceOf(AssistenteIndisponivelException.class);
    assertThat(semModelo.identificacao()).isEqualTo("none");
    assertThat(disponibilidade.disponivel()).isFalse();
  }

  @Test
  @DisplayName("Dois ChatModel no contexto viram 503, nao 500 generico de NF-e")
  void chatModelAmbiguoResponde503() {
    StaticListableBeanFactory beans = new StaticListableBeanFactory();
    beans.addBean("um", new ChatModelSimulado());
    beans.addBean("outro", new ChatModelSimulado());
    ModeloLinguagem ambiguo =
        new ModeloLinguagem(beans.getBeanProvider(ChatModel.class), "ollama", disponibilidade);

    assertThatThrownBy(() -> ambiguo.chamar(new Prompt("pergunta")))
        .isInstanceOf(AssistenteIndisponivelException.class);
    assertThat(ambiguo.identificacao()).isEqualTo("ollama");
  }

  @Test
  @DisplayName("Identificacao para auditoria: provedor/modelo")
  void identificacaoProvedorEModelo() {
    assertThat(modelo.identificacao()).isEqualTo("ollama/simulado");
  }

  @Test
  @DisplayName("#146: falha abre o disjuntor pela pausa; vencida, o modelo e tentado de novo")
  void disjuntorAbreEFechaPelaPausa() {
    chatModel.falharCom(new ResourceAccessException("Connection refused"));
    assertThatThrownBy(() -> modelo.chamar(new Prompt("pergunta")))
        .isInstanceOf(AssistenteIndisponivelException.class);
    assertThat(disponibilidade.disponivel()).isFalse();

    agora.addAndGet(PAUSA.toNanos() - 1);
    assertThat(disponibilidade.disponivel()).isFalse();
    agora.addAndGet(1);
    assertThat(disponibilidade.disponivel()).isTrue();

    // Meia-abertura: a proxima chamada que responde fecha o disjuntor.
    chatModel.responderTexto("voltei");
    modelo.chamar(new Prompt("pergunta"));
    assertThat(disponibilidade.disponivel()).isTrue();
  }

  @Test
  @DisplayName("#146: recusa do provedor a esta pergunta (4xx) e resposta vazia nao abrem o disjuntor")
  void falhaDaPerguntaNaoAbreDisjuntor() {
    // Uma pergunta feita para ser recusada nao pode mandar todos os produtores ao fallback.
    chatModel.falharCom(new NonTransientAiException("HTTP 400 - content_policy_violation"));
    assertThatThrownBy(() -> modelo.chamar(new Prompt("pergunta")))
        .isInstanceOf(AssistenteIndisponivelException.class);
    assertThat(disponibilidade.disponivel()).isTrue();

    chatModel.responderCom(prompt -> new ChatResponse(List.of()));
    assertThatThrownBy(() -> modelo.chamar(new Prompt("pergunta")))
        .isInstanceOf(AssistenteIndisponivelException.class);
    assertThat(disponibilidade.disponivel()).isTrue();

    // Queda de verdade (transitoria) abre.
    chatModel.falharCom(new TransientAiException("HTTP 503 do provedor"));
    assertThatThrownBy(() -> modelo.chamar(new Prompt("pergunta")))
        .isInstanceOf(AssistenteIndisponivelException.class);
    assertThat(disponibilidade.disponivel()).isFalse();
  }

  @Test
  @DisplayName("#146: thread interrompida (pergunta cancelada) nao abre o disjuntor dos outros")
  void interrupcaoNaoAbreDisjuntor() {
    chatModel.falharCom(new ResourceAccessException("I/O interrompido"));
    Thread.currentThread().interrupt();
    try {
      assertThatThrownBy(() -> modelo.chamar(new Prompt("pergunta")))
          .isInstanceOf(AssistenteIndisponivelException.class);
    } finally {
      Thread.interrupted();
    }

    assertThat(disponibilidade.disponivel()).isTrue();
  }

  private static ModeloLinguagem gatewayCom(
      ChatModel chatModel, DisponibilidadeModelo disponibilidade) {
    StaticListableBeanFactory beans = new StaticListableBeanFactory();
    beans.addBean("chatModel", chatModel);
    return new ModeloLinguagem(beans.getBeanProvider(ChatModel.class), "ollama", disponibilidade);
  }
}
