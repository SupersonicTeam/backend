package br.com.agrocontabil.assistente.llm;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.support.ChatModelSimulado;
import br.com.agrocontabil.support.EmbeddingModelSimulado;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * Fiação do profile {@code test} (issue #141): os modelos reais ficam de fora e o
 * {@link ModeloLinguagem} conversa com o {@link ChatModelSimulado}. Se o
 * {@code application-test.yml} perder {@code spring.ai.model.*: none}, o Ollama volta ao contexto
 * ao lado do simulado — este teste pega isso antes dos testes da Ceres quebrarem de forma confusa.
 */
@SpringBootTest
@ActiveProfiles("test")
class ModelosSimuladosIntegrationTest {

  @Autowired
  private ApplicationContext contexto;

  @Autowired
  private ModeloLinguagem modeloLinguagem;

  @Autowired
  private ChatModelSimulado chatModel;

  @BeforeEach
  void setUp() {
    chatModel.reiniciar();
  }

  @Test
  @DisplayName("Um unico ChatModel e um unico EmbeddingModel, ambos simulados")
  void somenteModelosSimulados() {
    assertThat(contexto.getBeansOfType(ChatModel.class).values())
        .singleElement().isInstanceOf(ChatModelSimulado.class);
    assertThat(contexto.getBeansOfType(EmbeddingModel.class).values())
        .singleElement().isInstanceOf(EmbeddingModelSimulado.class);
  }

  @Test
  @DisplayName("Sem Ollama no profile test: nenhum health indicator tentando a rede")
  void semHealthDoOllama() {
    assertThat(contexto.getBeansOfType(OllamaHealthIndicator.class)).isEmpty();
  }

  @Test
  @DisplayName("O gateway usa o modelo simulado")
  void gatewayUsaSimulado() {
    chatModel.responderTexto("ok da Ceres");

    String texto = modeloLinguagem.chamar(new Prompt("oi")).getResult().getOutput().getText();

    assertThat(texto).isEqualTo("ok da Ceres");
    assertThat(chatModel.promptsRecebidos()).hasSize(1);
  }
}
