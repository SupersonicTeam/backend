package br.com.agrocontabil.assistente.llm;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.assistente.ContextoAssistente;
import br.com.agrocontabil.assistente.PromptsCeres;
import br.com.agrocontabil.assistente.fidelidade.ConferenciaFidelidadeLei;
import br.com.agrocontabil.assistente.fidelidade.JuizFidelidadeLei;
import br.com.agrocontabil.config.AssistenteProperties;
import br.com.agrocontabil.config.AssistenteProperties.FidelidadeLei.Juiz.Configuracao;
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
 * autoconfiguração com o {@code application.yml} de verdade (e o {@code AssistenteConfig}, que põe
 * o {@code InterceptorRaciocinioAnthropic} no {@code RestClient}), fala com um servidor HTTP local
 * que guarda cada corpo e cada cabeçalho recebidos. A prova não depende de uma chamada real.
 *
 * <ul>
 *   <li>As outras chamadas saem sempre com o raciocínio desligado ({@code thinking.type = disabled})
 *       e sem {@code output_config}, qualquer que seja a configuração do juiz.</li>
 *   <li>O juiz nas configurações {@code atual} e {@code justificativa}, também; muda só o
 *       {@code max_tokens} e a instrução.</li>
 *   <li>O juiz nas adaptativas sai com {@code thinking.type = adaptive}, o
 *       {@code output_config.effort} do nível e o {@code max_tokens} do
 *       {@code tetos.juiz-com-raciocinio}; a resposta começa por um bloco {@code thinking} vazio, e o
 *       juiz lê os vereditos do bloco de texto e os tokens de raciocínio do {@code usage}.</li>
 *   <li>O cabeçalho-marca nunca chega ao servidor.</li>
 * </ul>
 */
class CorpoDoJuizNoFioTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final List<ConferenciaFidelidadeLei.FraseConferida> FRASES = List.of(
      new ConferenciaFidelidadeLei.FraseConferida("Os insumos têm redução de 60%.",
          List.of("Art. 138. Ficam reduzidas em 60% as alíquotas dos insumos.")));

  private record Recebida(String corpo, boolean comMarca) {
  }

  private final Deque<String> respostas = new ArrayDeque<>();
  private final List<Recebida> recebidas = new ArrayList<>();
  private HttpServer servidor;

  @BeforeEach
  void setUp() throws IOException {
    servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    servidor.createContext("/", troca -> {
      String corpoRecebido = new String(troca.getRequestBody().readAllBytes(),
          StandardCharsets.UTF_8);
      String resposta;
      synchronized (respostas) {
        recebidas.add(new Recebida(corpoRecebido,
            troca.getRequestHeaders().containsKey(RaciocinioPorChamada.CABECALHO)));
        resposta = respostas.pop();
      }
      byte[] corpo = resposta.getBytes(StandardCharsets.UTF_8);
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

  /** Resposta da API com um bloco de texto e, opcionalmente, um de raciocínio antes dele. */
  private static String resposta(String texto, boolean comRaciocinio) throws IOException {
    String blocoTexto = "{\"type\":\"text\",\"text\":" + JSON.writeValueAsString(texto) + "}";
    String conteudo = comRaciocinio
        ? "{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"assinatura\"}," + blocoTexto
        : blocoTexto;
    String detalhe = comRaciocinio ? ",\"output_tokens_details\":{\"thinking_tokens\":123}" : "";
    return "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
        + "\"model\":\"claude-haiku-5-5\",\"content\":[" + conteudo + "],"
        + "\"stop_reason\":\"end_turn\",\"stop_sequence\":null,"
        + "\"usage\":{\"input_tokens\":3,\"output_tokens\":" + (comRaciocinio ? 140 : 10)
        + detalhe + "}}";
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(Configuracao.class)
  @DisplayName("no fio: raciocínio só no juiz das adaptativas; nas outras chamadas, desligado")
  void corpoNoFio(Configuracao configuracao) {
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

          // Uma chamada como a da rota de lei, o juiz, e outra chamada depois dele.
          respostas.add(resposta("texto", false));
          contexto.chamar(modelo, new Prompt(List.of(new SystemMessage("sistema"),
              new UserMessage("pergunta")), ModeloLinguagem.comTeto(1024)));
          respostas.add(resposta("{\"vereditos\": [\"SUSTENTA\"]}", configuracao.raciocina()));
          assertThat(new JuizFidelidadeLei(modelo, propriedades).julgar(FRASES, contexto))
              .isNull();
          respostas.add(resposta("texto", false));
          contexto.chamar(modelo, new Prompt(List.of(new SystemMessage("sistema"),
              new UserMessage("pergunta")), ModeloLinguagem.comTeto(512)));

          assertThat(recebidas).hasSize(3);
          assertThat(recebidas).noneMatch(Recebida::comMarca);
          JsonNode juiz = JSON.readTree(recebidas.get(1).corpo());
          for (int i : new int[] {0, 2}) {
            JsonNode outra = JSON.readTree(recebidas.get(i).corpo());
            assertThat(outra.path("thinking").path("type").asText()).isEqualTo("disabled");
            assertThat(outra.has("output_config")).isFalse();
          }
          for (JsonNode corpo : List.of(juiz, JSON.readTree(recebidas.get(0).corpo()))) {
            assertThat(corpo.path("model").asText()).isEqualTo("claude-haiku-5-5");
            assertThat(corpo.path("thinking").has("budget_tokens")).isFalse();
          }
          assertThat(juiz.path("max_tokens").asInt())
              .isEqualTo(propriedades.tetoSaidaDoJuiz(configuracao));
          if (configuracao.raciocina()) {
            assertThat(juiz.path("thinking").path("type").asText()).isEqualTo("adaptive");
            assertThat(juiz.path("output_config").path("effort").asText())
                .isEqualTo(configuracao.esforco());
            assertThat(juiz.path("max_tokens").asInt()).isEqualTo(3072);
            assertThat(juiz.toString()).contains("um veredito por frase");
            assertThat(contexto.juizLei().tokensRaciocinio()).isEqualTo(123);
          } else {
            assertThat(juiz.path("thinking").path("type").asText()).isEqualTo("disabled");
            assertThat(juiz.has("output_config")).isFalse();
            assertThat(juiz.path("max_tokens").asInt())
                .isEqualTo(configuracao == Configuracao.JUSTIFICATIVA ? 1024 : 128);
            assertThat(juiz.toString()).contains(configuracao == Configuracao.JUSTIFICATIVA
                ? "justificativa" : "um veredito por frase");
            assertThat(contexto.juizLei().tokensRaciocinio()).isNull();
          }
          assertThat(PromptsCeres.JUIZ_FIDELIDADE).contains("um veredito por frase");
        });
  }
}
