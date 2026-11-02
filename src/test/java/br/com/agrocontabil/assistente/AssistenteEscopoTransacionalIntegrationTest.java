package br.com.agrocontabil.assistente;

import static br.com.agrocontabil.support.DadosCeresFixture.em;
import static br.com.agrocontabil.support.DadosCeresFixture.entrada;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.config.MockIdentidadeFilter;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.support.ChatModelSimulado;
import br.com.agrocontabil.support.IdentidadeMockadaConfig;
import br.com.agrocontabil.support.TestDatabaseCleaner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;

/**
 * O chat não pode derrubar a emissão (issue #157, mesma lição das #96 e #99).
 *
 * <p><strong>Pool de 1 conexão é o instrumento de medida</strong> (padrão do
 * {@code NotaFiscalEscopoTransacionalIntegrationTest}). A pergunta passa por uma função de dados
 * (que usa o banco) e o modelo simulado então "pensa", bloqueado. Nesse meio tempo, outra
 * requisição ({@code GET /api/notas}) precisa da única conexão do pool. Se a Ceres segurasse uma
 * transação em volta da chamada ao modelo — ou a função devolvesse a conexão só no fim da
 * pergunta —, a outra requisição esperaria o {@code connection-timeout} e falharia.
 *
 * <p>Falsificado repondo {@code @Transactional} no {@code OrquestradorService.responder}: o
 * {@code GET /api/notas} falha com a conexão presa (e o {@code ArquiteturaAssistenteTest} também
 * quebra, que é a primeira linha de defesa).
 *
 * <p>Sem {@code @Transactional} na classe: uma transação de teste esconderia o cenário.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.hikari.maximum-pool-size=1",
    "spring.datasource.hikari.minimum-idle=1",
    // Timeout curto: a exaustao do pool FALHA o teste em vez de pendura-lo.
    "spring.datasource.hikari.connection-timeout=2000",
    "app.assistente.timeout-pergunta=30s"})
@Import({TestDatabaseCleaner.class, IdentidadeMockadaConfig.class})
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
@DirtiesContext(classMode = ClassMode.AFTER_CLASS)
class AssistenteEscopoTransacionalIntegrationTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @LocalServerPort private int porta;
  @Autowired private ProdutorRepository produtorRepository;
  @Autowired private NotaFiscalRepository notaFiscalRepository;
  @Autowired private ChatModelSimulado modelo;
  @Autowired private TestDatabaseCleaner cleaner;

  private final HttpClient http = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(5)).build();
  private final CountDownLatch liberarModelo = new CountDownLatch(1);
  private Produtor produtorA;
  private Produtor produtorB;

  @BeforeEach
  void setUp() {
    cleaner.limparTudo();
    modelo.reiniciar();
    produtorA = produtorRepository.save(Produtor.builder()
        .nomeCompleto("Fazenda A").cpfCnpj("98765432100").estado("MT").build());
    produtorB = produtorRepository.save(Produtor.builder()
        .nomeCompleto("Fazenda B").cpfCnpj("11122233344").estado("PR").build());
    notaFiscalRepository.save(entrada(produtorA, StatusNotaFiscal.AUTORIZADA, em(2026, 2, 10),
        "FERTILIZANTE NPK 04-14-08", "31052000", "KG", "1000", "2500.00"));
    notaFiscalRepository.save(entrada(produtorB, StatusNotaFiscal.AUTORIZADA, em(2026, 3, 5),
        "Semente de soja", "12019000", "KG", "500", "900.00"));
  }

  @AfterEach
  void tearDown() {
    liberarModelo.countDown();
    modelo.reiniciar();
  }

  @Test
  @DisplayName("Com o modelo 'pensando' na escolha da funcao, outra requisicao consegue a unica "
      + "conexao do pool")
  void modeloPensandoNaoPrendeConexao() throws Exception {
    modelo.classificarComo("DADOS");
    CountDownLatch pensando = new CountDownLatch(1);
    // #266: o modelo e chamado uma vez so, para escolher a funcao; e ali que ele "pensa".
    modelo.responderCom(prompt -> {
      pensando.countDown();
      try {
        liberarModelo.await(30, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
      return pedirFuncao("totalComprasPorInsumo", Map.of("descricaoOuNcm", "fertilizante",
          "dataInicio", "2026-01-01", "dataFim", "2026-06-30"));
    });

    CompletableFuture<HttpResponse<String>> pergunta = http.sendAsync(
        post("/api/assistente/chat", produtorA, "{\"pergunta\":\"Quanto comprei de adubo?\"}"),
        HttpResponse.BodyHandlers.ofString());
    assertThat(pensando.await(10, TimeUnit.SECONDS)).as("modelo chamado").isTrue();

    long inicio = System.nanoTime();
    HttpResponse<String> notas = http.send(get("/api/notas", produtorB),
        HttpResponse.BodyHandlers.ofString());
    long decorridoMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - inicio);

    assertThat(notas.statusCode()).as("GET /api/notas com o chat em andamento: %s", notas.body())
        .isEqualTo(200);
    // Bem abaixo do connection-timeout: a conexao estava livre, ninguem esperou por ela.
    assertThat(decorridoMs).isLessThan(1500);
    assertThat(pergunta).isNotDone();

    liberarModelo.countDown();
    HttpResponse<String> resposta = pergunta.get(20, TimeUnit.SECONDS);
    assertThat(resposta.statusCode()).isEqualTo(200);
    JsonNode corpo = JSON.readTree(resposta.body());
    assertThat(corpo.path("fontesDados").get(0).path("funcao").asText())
        .isEqualTo("totalComprasPorInsumo");
  }

  private HttpRequest post(String caminho, Produtor produtor, String corpo) {
    return HttpRequest.newBuilder(URI.create("http://localhost:" + porta + caminho))
        .timeout(Duration.ofSeconds(30))
        .header("Content-Type", "application/json")
        .header(MockIdentidadeFilter.HEADER_USUARIO_ID, produtor.getId().toString())
        .POST(HttpRequest.BodyPublishers.ofString(corpo))
        .build();
  }

  private HttpRequest get(String caminho, Produtor produtor) {
    return HttpRequest.newBuilder(URI.create("http://localhost:" + porta + caminho))
        .timeout(Duration.ofSeconds(30))
        .header(MockIdentidadeFilter.HEADER_USUARIO_ID, produtor.getId().toString())
        .GET()
        .build();
  }

  private static Message ultimaMensagem(Prompt prompt) {
    List<Message> mensagens = prompt.getInstructions();
    return mensagens.get(mensagens.size() - 1);
  }

  private static ChatResponse pedirFuncao(String funcao, Map<String, Object> argumentos) {
    try {
      AssistantMessage pedido = AssistantMessage.builder()
          .content("")
          .toolCalls(List.of(new AssistantMessage.ToolCall(
              "chamada-1", "function", funcao, JSON.writeValueAsString(argumentos))))
          .build();
      return new ChatResponse(List.of(new Generation(pedido)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
