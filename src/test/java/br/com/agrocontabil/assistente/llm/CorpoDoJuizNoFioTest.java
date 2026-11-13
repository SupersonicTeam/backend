package br.com.agrocontabil.assistente.llm;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.assistente.ContextoAssistente;
import br.com.agrocontabil.assistente.PromptsCeres;
import br.com.agrocontabil.assistente.fidelidade.ConferenciaFidelidadeLei;
import br.com.agrocontabil.assistente.fidelidade.JuizFidelidadeLei;
import br.com.agrocontabil.config.AssistenteProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.anthropic.autoconfigure.AnthropicChatAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.bind.Binder;

/**
 * O corpo que sai para a API da Anthropic na chamada do juiz da fidelidade da lei (#269) e nas
 * outras, verificado <strong>no fio</strong>: o {@code ChatModel} real do Spring AI, montado pela
 * autoconfiguração com o {@code application.yml} de verdade, fala com um servidor HTTP local que
 * guarda cada corpo recebido. A prova não depende de uma chamada real.
 *
 * <p>Hoje, em toda configuração do juiz, todas as chamadas saem com o raciocínio desligado
 * ({@code thinking.type = disabled}) e sem {@code output_config}; o juiz muda só o
 * {@code max_tokens} (o teto da configuração) e a instrução. Quando entrar uma configuração com
 * raciocínio (as adaptativas), o corpo do juiz nela muda aqui, e o das outras chamadas tem de
 * continuar com o raciocínio desligado: este teste é a trava.
 */
class CorpoDoJuizNoFioTest {

  private static final String OK = "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
      + "\"model\":\"claude-haiku-5-5\",\"content\":[{\"type\":\"text\",\"text\":\"%s\"}],"
      + "\"stop_reason\":\"end_turn\",\"stop_sequence\":null,"
      + "\"usage\":{\"input_tokens\":3,\"output_tokens\":1}}";
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final List<ConferenciaFidelidadeLei.FraseConferida> FRASES = List.of(
      new ConferenciaFidelidadeLei.FraseConferida("Os insumos têm redução de 60%.",
          List.of("Art. 138. Ficam reduzidas em 60% as alíquotas dos insumos.")));

  private final Deque<String> respostas = new ArrayDeque<>();
  private final List<String> corpos = new ArrayList<>();
  private HttpServer servidor;

  @BeforeEach
  void setUp() throws IOException {
    servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    servidor.createContext("/", troca -> {
      String corpoRecebido = new String(troca.getRequestBody().readAllBytes(),
          StandardCharsets.UTF_8);
      String texto;
      synchronized (respostas) {
        corpos.add(corpoRecebido);
        texto = respostas.pop();
      }
      byte[] corpo = OK.formatted(texto.replace("\"", "\\\"")).getBytes(StandardCharsets.UTF_8);
      troca.getResponseHeaders().add("Content-Type", "application/json");
      troca.sendResponseHeaders(200, corpo.length);
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
  @EnumSource(AssistenteProperties.FidelidadeLei.Juiz.Configuracao.class)
  @DisplayName("juiz e outras chamadas: raciocínio desligado no corpo; o juiz muda só o teto e a "
      + "instrução da configuração")
  void corpoNoFio(AssistenteProperties.FidelidadeLei.Juiz.Configuracao configuracao) {
    ProvedorModeloSelecaoTest.contextoDoApplicationYml()
        .withConfiguration(AutoConfigurations.of(AnthropicChatAutoConfiguration.class))
        .withPropertyValues("AI_CHAT_PROVIDER=anthropic", "ANTHROPIC_API_KEY=chave-de-teste",
            "spring.ai.anthropic.base-url=http://127.0.0.1:" + servidor.getAddress().getPort(),
            "AI_FIDELIDADE_LEI_HABILITADA=true", "AI_FIDELIDADE_LEI_JUIZ_HABILITADO=true",
            // O nome, como os bracos da medicao o passam: prova tambem o vinculo do yml.
            "AI_FIDELIDADE_LEI_JUIZ_CONFIGURACAO=" + configuracao.nome())
        .run(ctx -> {
          assertThat(ctx).hasNotFailed();
          AssistenteProperties propriedades = Binder.get(ctx.getEnvironment())
              .bind("app.assistente", AssistenteProperties.class).get();
          assertThat(propriedades.getFidelidadeLei().getJuiz().getConfiguracao())
              .isEqualTo(configuracao);
          ModeloLinguagem modelo = new ModeloLinguagem(ctx.getBeanProvider(ChatModel.class),
              "anthropic", new DisponibilidadeModelo(Duration.ofSeconds(30), System::nanoTime));
          ContextoAssistente contexto = new ContextoAssistente(UUID.randomUUID(),
              LocalDate.of(2026, 11, 13));

          // Uma chamada como a da rota de lei, e o juiz.
          respostas.add("texto");
          contexto.chamar(modelo, new Prompt(List.of(new SystemMessage("sistema"),
              new UserMessage("pergunta")), ModeloLinguagem.comTeto(1024)));
          respostas.add("{\"vereditos\": [\"SUSTENTA\"]}");
          assertThat(new JuizFidelidadeLei(modelo, propriedades).julgar(FRASES, contexto))
              .isNull();

          assertThat(corpos).hasSize(2);
          JsonNode rota = JSON.readTree(corpos.get(0));
          JsonNode juiz = JSON.readTree(corpos.get(1));
          for (JsonNode corpo : List.of(rota, juiz)) {
            assertThat(corpo.path("model").asText()).isEqualTo("claude-haiku-5-5");
            assertThat(corpo.path("thinking").path("type").asText()).isEqualTo("disabled");
            assertThat(corpo.has("output_config")).isFalse();
            assertThat(corpo.path("thinking").has("budget_tokens")).isFalse();
          }
          assertThat(rota.path("max_tokens").asInt()).isEqualTo(1024);
          assertThat(juiz.path("max_tokens").asInt())
              .isEqualTo(propriedades.tetoSaidaDoJuiz(configuracao))
              .isEqualTo(configuracao
                  == AssistenteProperties.FidelidadeLei.Juiz.Configuracao.JUSTIFICATIVA
                  ? 512 : 128);
          assertThat(juiz.toString()).contains(configuracao
              == AssistenteProperties.FidelidadeLei.Juiz.Configuracao.JUSTIFICATIVA
              ? "justificativa" : "um veredito por frase");
        });
  }
}
