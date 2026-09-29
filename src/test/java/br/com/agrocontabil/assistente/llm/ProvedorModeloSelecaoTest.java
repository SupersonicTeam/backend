package br.com.agrocontabil.assistente.llm;

import static org.assertj.core.api.Assertions.assertThat;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.agrocontabil.config.AssistenteConfig;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.model.ollama.autoconfigure.OllamaApiAutoConfiguration;
import org.springframework.ai.model.ollama.autoconfigure.OllamaChatAutoConfiguration;
import org.springframework.ai.model.ollama.autoconfigure.OllamaEmbeddingAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.ollama.api.ThinkOption;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.autoconfigure.web.reactive.function.client.WebClientAutoConfiguration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.ResourceAccessException;

/**
 * Trava a DoD "trocar para um provedor por API sem alterar código" (issue #141) contra o
 * {@code application.yml} de verdade, com as autoconfigurações reais do Spring AI.
 *
 * <p>Nenhuma chamada de rede acontece: os modelos são só instanciados (o pull de modelo está em
 * {@code never}).
 */
class ProvedorModeloSelecaoTest {

  private final ApplicationContextRunner contexto = new ApplicationContextRunner()
      .withInitializer(ctx -> ctx.getEnvironment().getPropertySources()
          .addLast(applicationYml()))
      .withUserConfiguration(AssistenteConfig.class)
      .withConfiguration(AutoConfigurations.of(
          JacksonAutoConfiguration.class,
          HttpMessageConvertersAutoConfiguration.class,
          RestClientAutoConfiguration.class,
          WebClientAutoConfiguration.class,
          SpringAiRetryAutoConfiguration.class,
          ToolCallingAutoConfiguration.class,
          OllamaApiAutoConfiguration.class,
          OllamaChatAutoConfiguration.class,
          OllamaEmbeddingAutoConfiguration.class,
          OpenAiChatAutoConfiguration.class,
          OpenAiEmbeddingAutoConfiguration.class,
          OpenAiImageAutoConfiguration.class,
          OpenAiAudioSpeechAutoConfiguration.class,
          OpenAiAudioTranscriptionAutoConfiguration.class,
          OpenAiModerationAutoConfiguration.class));

  @Test
  @DisplayName("Padrao: chat e embedding pelo Ollama, sem exigir chave de API")
  void padraoOllamaSemChave() {
    contexto.run(ctx -> {
      assertThat(ctx).hasNotFailed();
      assertThat(ctx).hasSingleBean(ChatModel.class);
      assertThat(ctx.getBean(ChatModel.class)).isInstanceOf(OllamaChatModel.class);
      assertThat(ctx.getBean(ChatModel.class).getDefaultOptions().getModel())
          .isEqualTo("qwen3:4b");
      // Contexto explicito: estourado, o Ollama descartaria o inicio do prompt (as regras).
      OllamaChatOptions opcoes = (OllamaChatOptions) ctx.getBean(ChatModel.class)
          .getDefaultOptions();
      assertThat(opcoes.getNumCtx()).isEqualTo(8192);
      assertThat(opcoes.getNumPredict()).isEqualTo(1024);
      // Raciocinio desligado: os tokens de thinking consumiriam o num-predict.
      assertThat(opcoes.getThinkOption()).isEqualTo(ThinkOption.ThinkBoolean.DISABLED);
      // O RetryTemplate do projeto substitui o do Spring AI: read-timeout nao e re-tentado.
      AtomicInteger tentativas = new AtomicInteger();
      assertThatThrownBy(() -> ctx.getBean(RetryTemplate.class).execute(c -> {
        tentativas.incrementAndGet();
        throw new ResourceAccessException("I/O", new SocketTimeoutException("Read timed out"));
      })).isInstanceOf(ResourceAccessException.class);
      assertThat(tentativas).hasValue(1);
      assertThat(ctx).hasSingleBean(EmbeddingModel.class);
      assertThat(ctx.getBean(EmbeddingModel.class)).isInstanceOf(OllamaEmbeddingModel.class);
      // Imagem, audio e moderacao do starter da OpenAI desligados: exigiriam chave na subida.
      assertThat(ctx.getBeanNamesForType(ImageModel.class)).isEmpty();
    });
  }

  @Test
  @DisplayName("AI_CHAT_PROVIDER=openai troca o chat por configuracao; embedding segue local")
  void provedorPorApiSoPorConfiguracao() {
    contexto
        .withPropertyValues("AI_CHAT_PROVIDER=openai", "AI_API_KEY=chave-de-teste",
            "AI_CHAT_MODEL=sabia-3")
        .run(ctx -> {
          assertThat(ctx).hasNotFailed();
          assertThat(ctx).hasSingleBean(ChatModel.class);
          assertThat(ctx.getBean(ChatModel.class)).isInstanceOf(OpenAiChatModel.class);
          assertThat(ctx.getBean(ChatModel.class).getDefaultOptions().getModel())
              .isEqualTo("sabia-3");
          // O embedding nunca sai da maquina: trocar o modelo obrigaria a reindexar a lei.
          assertThat(ctx.getBean(EmbeddingModel.class)).isInstanceOf(OllamaEmbeddingModel.class);
        });
  }

  @Test
  @DisplayName("Provedor por API sem chave falha na subida, nao na primeira pergunta")
  void provedorPorApiSemChaveFalhaNaSubida() {
    contexto
        .withPropertyValues("AI_CHAT_PROVIDER=openai")
        .run(ctx -> {
          assertThat(ctx).hasFailed();
          // A falha tem de ser a chave ausente, nao qualquer outra quebra de contexto.
          assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("API key");
        });
  }

  private static PropertySource<?> applicationYml() {
    try {
      List<PropertySource<?>> fontes = new YamlPropertySourceLoader()
          .load("application.yml", new ClassPathResource("application.yml"));
      return fontes.get(0);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
