package br.com.agrocontabil.assistente.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.agrocontabil.exception.AssistenteIndisponivelException;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.anthropic.autoconfigure.AnthropicChatAutoConfiguration;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * O HTTP 429 do provedor de chat por API, verificado <strong>no fio</strong>: o {@code ChatModel}
 * real de cada provedor, montado pelas autoconfigurações do Spring AI com o
 * {@code application.yml} de verdade, fala com um servidor HTTP local que devolve os status
 * enfileirados.
 *
 * <p>É o que os testes com modelo simulado não provam: que o tratador de erro do projeto é o que o
 * provedor usa, que a {@link LimiteTaxaProvedorException} atravessa o {@code RestClient} e o
 * {@code ChatModel} sem perder o tipo e que, esgotadas as tentativas, o disjuntor fica fechado.
 * Só o intervalo do backoff é reduzido; as tentativas ({@code max-attempts}) são as do yml.
 */
class LimiteTaxaProvedorFioTest {

  private static final String ERRO = "{\"type\":\"error\",\"error\":{\"type\":\"erro_de_teste\","
      + "\"message\":\"corpo de erro do provedor\"}}";

  /** Provedores por API, com as variáveis que o {@code application.yml} lê e a resposta 200. */
  enum Provedor {
    ANTHROPIC("AI_CHAT_PROVIDER=anthropic", "ANTHROPIC_API_KEY=chave-de-teste",
        "spring.ai.anthropic.base-url=",
        "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
            + "\"model\":\"claude-haiku-5-5\",\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],"
            + "\"stop_reason\":\"end_turn\",\"stop_sequence\":null,"
            + "\"usage\":{\"input_tokens\":3,\"output_tokens\":1}}"),
    OPENAI("AI_CHAT_PROVIDER=openai", "AI_API_KEY=chave-de-teste", "AI_API_BASE_URL=",
        "{\"id\":\"c1\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"modelo\","
            + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},"
            + "\"finish_reason\":\"stop\"}],"
            + "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":1,\"total_tokens\":4}}");

    private final String selecao;
    private final String chave;
    private final String enderecoBase;
    private final String respostaOk;

    Provedor(String selecao, String chave, String enderecoBase, String respostaOk) {
      this.selecao = selecao;
      this.chave = chave;
      this.enderecoBase = enderecoBase;
      this.respostaOk = respostaOk;
    }
  }

  private record Resposta(int status, String corpo) {
  }

  private final Deque<Resposta> respostas = new ArrayDeque<>();
  private final AtomicInteger requisicoes = new AtomicInteger();
  private HttpServer servidor;

  @BeforeEach
  void setUp() throws IOException {
    servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    servidor.createContext("/", troca -> {
      troca.getRequestBody().readAllBytes();
      requisicoes.incrementAndGet();
      Resposta resposta;
      synchronized (respostas) {
        resposta = respostas.pop();
      }
      byte[] corpo = resposta.corpo().getBytes(StandardCharsets.UTF_8);
      troca.getResponseHeaders().add("Content-Type", "application/json");
      troca.sendResponseHeaders(resposta.status(), corpo.length);
      try (OutputStream saida = troca.getResponseBody()) {
        saida.write(corpo);
      }
    });
    servidor.start();
  }

  @AfterEach
  void tearDown() {
    servidor.stop(0);
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(Provedor.class)
  @DisplayName("429 no fio: repetido ate max-attempts e, esgotado, nao abre o disjuntor")
  void limiteDeTaxaNoFio(Provedor provedor) {
    contextoDe(provedor).run(ctx -> {
      assertThat(ctx).hasNotFailed();

      // 429 nas duas tentativas do application.yml: falha da pergunta, disjuntor fechado.
      DisponibilidadeModelo disjuntor = disjuntor();
      ModeloLinguagem modelo = porta(ctx.getBeanProvider(ChatModel.class), provedor, disjuntor);
      enfileirar(429, 429);
      assertThatThrownBy(() -> modelo.chamar(new Prompt("pergunta")))
          .isInstanceOf(AssistenteIndisponivelException.class)
          .hasCauseInstanceOf(LimiteTaxaProvedorException.class)
          // A mensagem que vai ao log nao leva o corpo do provedor.
          .satisfies(e -> assertThat(e.getMessage()).doesNotContain("corpo de erro", "HTTP"));
      assertThat(requisicoes.getAndSet(0)).isEqualTo(2);
      assertThat(disjuntor.disponivel()).isTrue();

      // 429 e depois 200: a nova tentativa responde e a pergunta nem percebe.
      enfileirar(429);
      enfileirar(new Resposta(200, provedor.respostaOk));
      ChatResponse resposta = modelo.chamar(new Prompt("pergunta"));
      assertThat(resposta.getResult().getOutput().getText()).isEqualTo("ok");
      assertThat(requisicoes.getAndSet(0)).isEqualTo(2);
      assertThat(disjuntor.disponivel()).isTrue();
    });
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(Provedor.class)
  @DisplayName("No fio: 5xx segue repetido e abrindo o disjuntor; 401 nao repete nem abre")
  void demaisErrosNoFioComoAntes(Provedor provedor) {
    contextoDe(provedor).run(ctx -> {
      assertThat(ctx).hasNotFailed();

      DisponibilidadeModelo aposQueda = disjuntor();
      enfileirar(503, 503);
      assertThatThrownBy(() -> porta(ctx.getBeanProvider(ChatModel.class), provedor, aposQueda)
          .chamar(new Prompt("pergunta")))
          .isInstanceOf(AssistenteIndisponivelException.class)
          .hasCauseInstanceOf(TransientAiException.class)
          .cause().isNotInstanceOf(LimiteTaxaProvedorException.class);
      assertThat(requisicoes.getAndSet(0)).isEqualTo(2);
      assertThat(aposQueda.disponivel()).isFalse();

      DisponibilidadeModelo aposRecusa = disjuntor();
      enfileirar(401);
      assertThatThrownBy(() -> porta(ctx.getBeanProvider(ChatModel.class), provedor, aposRecusa)
          .chamar(new Prompt("pergunta")))
          .isInstanceOf(AssistenteIndisponivelException.class)
          .hasCauseInstanceOf(NonTransientAiException.class);
      assertThat(requisicoes.getAndSet(0)).isEqualTo(1);
      assertThat(aposRecusa.disponivel()).isTrue();
    });
  }

  private ApplicationContextRunner contextoDe(Provedor provedor) {
    return ProvedorModeloSelecaoTest.contextoDoApplicationYml()
        .withConfiguration(AutoConfigurations.of(AnthropicChatAutoConfiguration.class))
        .withPropertyValues(provedor.selecao, provedor.chave,
            provedor.enderecoBase + "http://127.0.0.1:" + servidor.getAddress().getPort(),
            "AI_CHAT_MODEL=modelo",
            // So o intervalo: esperar o segundo de producao a cada caso nao prova nada a mais.
            "spring.ai.retry.backoff.initial-interval=1ms",
            "spring.ai.retry.backoff.max-interval=1ms");
  }

  private void enfileirar(int... status) {
    for (int codigo : status) {
      enfileirar(new Resposta(codigo, ERRO));
    }
  }

  private void enfileirar(Resposta resposta) {
    synchronized (respostas) {
      respostas.add(resposta);
    }
  }

  private static DisponibilidadeModelo disjuntor() {
    return new DisponibilidadeModelo(Duration.ofSeconds(30), System::nanoTime);
  }

  private static ModeloLinguagem porta(
      ObjectProvider<ChatModel> chatModel, Provedor provedor, DisponibilidadeModelo disjuntor) {
    return new ModeloLinguagem(chatModel, provedor.name().toLowerCase(Locale.ROOT), disjuntor);
  }
}
