package br.com.agrocontabil.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

/**
 * Trava a configuração do provedor Anthropic da Ceres: o bloco {@code spring.ai.anthropic} do
 * {@code application.yml} e a chave obrigatória na subida.
 *
 * <p>O que o yml fixa só aparece numa chamada real: temperatura fora do padrão da API volta 400 e
 * raciocínio ligado gasta o teto de cada chamada (#270). Sem este teste, uma troca ali passaria
 * pela suíte e só falharia em produção.
 */
class AnthropicConfiguracaoTest {

  private static final String OPCOES = "spring.ai.anthropic.chat.options";

  private final ApplicationContextRunner contexto =
      new ApplicationContextRunner().withUserConfiguration(AnthropicApiKeyConfig.class);

  @Test
  @DisplayName("yml: Haiku 5.5, temperatura no padrao da API, raciocinio desligado e teto geral")
  void opcoesPadraoDoYml() throws IOException {
    AnthropicChatOptions opcoes = opcoesCom(new MockEnvironment());

    assertThat(opcoes.getModel()).isEqualTo("claude-haiku-5-5");
    assertThat(opcoes.getTemperature()).isEqualTo(1.0);
    assertThat(opcoes.getMaxTokens()).isEqualTo(1024);
    assertThat(opcoes.getThinking()).isNotNull();
    assertThat(opcoes.getThinking().type()).isEqualTo(AnthropicApi.ThinkingType.DISABLED);
    assertThat(opcoes.getThinking().budgetTokens()).isNull();
  }

  @Test
  @DisplayName("yml: o modelo da Anthropic tem variavel propria, nao o AI_CHAT_MODEL do Ollama")
  void modeloNaoVemDoAiChatModel() throws IOException {
    MockEnvironment ambiente = new MockEnvironment()
        .withProperty("AI_CHAT_MODEL", "qwen3:4b-instruct")
        .withProperty("AI_ANTHROPIC_MODEL", "claude-outro-modelo");

    assertThat(opcoesCom(ambiente).getModel()).isEqualTo("claude-outro-modelo");
    assertThat(opcoesCom(new MockEnvironment().withProperty("AI_CHAT_MODEL", "qwen3:4b-instruct"))
        .getModel()).isEqualTo("claude-haiku-5-5");
  }

  @Test
  @DisplayName("yml: AI_ANTHROPIC_THINKING vazio nao envia o campo de raciocinio")
  void raciocinioVazioNaoEnviaOCampo() throws IOException {
    AnthropicChatOptions opcoes =
        opcoesCom(new MockEnvironment().withProperty("AI_ANTHROPIC_THINKING", ""));

    assertThat(opcoes.getThinking()).isNull();
  }

  @Test
  @DisplayName("subida recusada com o provedor anthropic e a chave em branco")
  void semChaveNaoSobe() {
    contexto.withPropertyValues("spring.ai.model.chat=anthropic", "spring.ai.anthropic.api-key= ")
        .run(ctx -> assertThat(ctx).hasFailed().getFailure().rootCause()
            .isInstanceOf(IllegalStateException.class)
            .hasMessage(AnthropicApiKeyConfig.MENSAGEM));
    contexto.withPropertyValues("spring.ai.model.chat=anthropic")
        .run(ctx -> assertThat(ctx).hasFailed());
  }

  @Test
  @DisplayName("com a chave, sobe; em outro provedor, a conferencia nem existe")
  void comChaveOuOutroProvedorSobe() {
    contexto.withPropertyValues("spring.ai.model.chat=anthropic", "spring.ai.anthropic.api-key=x")
        .run(ctx -> assertThat(ctx).hasNotFailed());
    contexto.withPropertyValues("spring.ai.model.chat=ollama")
        .run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean(AnthropicApiKeyConfig.class));
    contexto.run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean(AnthropicApiKeyConfig.class));
  }

  @Test
  @DisplayName("#499/#483: o pedido a API da Anthropic nao leva metadata.user_id, nem pelo yml "
      + "nem pelas opcoes de cada chamada")
  void pedidoSemMetadataUserId() throws IOException {
    AnthropicChatOptions padrao = opcoesCom(new MockEnvironment());
    assertThat(padrao.getMetadata()).isNull();

    AnthropicApi api = org.mockito.Mockito.mock(AnthropicApi.class);
    java.util.concurrent.atomic.AtomicReference<AnthropicApi.ChatCompletionRequest> enviado =
        new java.util.concurrent.atomic.AtomicReference<>();
    org.mockito.stubbing.Answer<Object> capturar = inv -> {
      enviado.set(inv.getArgument(0));
      throw new IllegalStateException("pedido capturado");
    };
    org.mockito.Mockito.when(api.chatCompletionEntity(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(capturar);
    org.mockito.Mockito.when(api.chatCompletionEntity(org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any())).thenAnswer(capturar);
    org.springframework.ai.anthropic.AnthropicChatModel modelo =
        org.springframework.ai.anthropic.AnthropicChatModel.builder()
            .anthropicApi(api)
            .defaultOptions(padrao)
            .retryTemplate(org.springframework.retry.support.RetryTemplate.builder()
                .maxAttempts(1).build())
            .build();
    // As opcoes de cada chamada sao as do ModeloLinguagem: so o teto de saida (#270).
    org.springframework.ai.chat.prompt.Prompt prompt = new org.springframework.ai.chat.prompt.Prompt(
        "quanto vendi de soja?",
        br.com.agrocontabil.assistente.llm.ModeloLinguagem.comTeto(256));

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> modelo.call(prompt))
        .hasMessageContaining("pedido capturado");

    assertThat(enviado.get()).isNotNull();
    assertThat(enviado.get().metadata()).isNull();
    assertThat(enviado.get().maxTokens()).isEqualTo(256);
  }

  /** Vincula o bloco do yml como o Boot faz, só com as variáveis do {@code ambiente}. */
  private static AnthropicChatOptions opcoesCom(MockEnvironment ambiente) throws IOException {
    for (PropertySource<?> fonte : new YamlPropertySourceLoader()
        .load("application", new ClassPathResource("application.yml"))) {
      ambiente.getPropertySources().addLast(fonte);
    }
    return Binder.get(ambiente).bind(OPCOES, AnthropicChatOptions.class).get();
  }
}
