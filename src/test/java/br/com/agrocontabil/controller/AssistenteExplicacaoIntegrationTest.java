package br.com.agrocontabil.controller;

import static br.com.agrocontabil.support.DadosCeresFixture.em;
import static br.com.agrocontabil.support.DadosCeresFixture.entrada;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.assistente.explicacao.PromptExplicacao;
import br.com.agrocontabil.config.MockIdentidadeFilter;
import br.com.agrocontabil.model.AssistenteInteracao;
import br.com.agrocontabil.model.DesfechoExplicacao;
import br.com.agrocontabil.model.DesfechoInteracao;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.model.TipoTitularCota;
import br.com.agrocontabil.model.UsoSemanalAssistente;
import br.com.agrocontabil.repository.AssistenteInteracaoRepository;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.repository.UsoSemanalAssistenteRepository;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;

/**
 * A explicação depois da resposta de dados (#268, com o que era da #415) de ponta a ponta, com
 * servidor real e a flag ligada, no padrão do {@code AssistenteStreamingIntegrationTest}: a ordem
 * dos eventos do SSE ({@code rota} → {@code resposta} → {@code explicacao} → {@code fim}), o
 * contrato do {@code POST /chat}, cada motivo de descarte com o {@link ChatModelSimulado} (só o
 * template sai, com o desfecho certo na auditoria), o prazo da explicação, o erro depois da
 * resposta e a cota com e sem saldo para a explicação.
 *
 * <p>A cota fica ligada nesta classe (no profile {@code test} ela é desligada), para provar o
 * acerto da explicação à parte da pergunta.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "app.assistente.max-conversas-simultaneas=2",
    "app.assistente.timeout-pergunta=4s",
    "app.assistente.timeout-funcao=1s",
    "app.assistente.explicacao.enabled=true",
    "app.assistente.explicacao.timeout=1s",
    "app.assistente.cota.habilitada=true"})
@Import({TestDatabaseCleaner.class, IdentidadeMockadaConfig.class})
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
class AssistenteExplicacaoIntegrationTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** Texto do template para as duas compras de fertilizante do produtor A. */
  private static final String TEMPLATE = "Entre 01/01/2026 e 30/06/2026 você comprou R$ 3.734,56"
      + " de fertilizante, em 2 notas de compra. Quantidade: 1.000 KG (R$ 2.500,00); 20 L"
      + " (R$ 1.234,56).";

  private static final String BOA = "Isso quer dizer que, no primeiro semestre, as suas compras"
      + " de fertilizante somaram R$ 3.734,56. Esse total junta as 2 notas de compra do período.";

  private static final String PERGUNTA = "Quanto comprei de fertilizante no semestre?";

  @LocalServerPort private int porta;
  @Autowired private ProdutorRepository produtorRepository;
  @Autowired private NotaFiscalRepository notaFiscalRepository;
  @Autowired private AssistenteInteracaoRepository interacaoRepository;
  @Autowired private UsoSemanalAssistenteRepository usoRepository;
  @Autowired private ChatModelSimulado modelo;
  @Autowired private TestDatabaseCleaner cleaner;
  @Autowired private JdbcTemplate jdbc;

  private final HttpClient http = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(5)).build();
  private final List<CountDownLatch> travas = new ArrayList<>();
  private Produtor produtorA;

  @BeforeEach
  void setUp() {
    cleaner.limparTudo();
    modelo.reiniciar();
    modelo.classificarComo("DADOS");
    produtorA = produtorRepository.save(Produtor.builder()
        .nomeCompleto("Fazenda A").cpfCnpj("98765432100").estado("MT").build());
    notaFiscalRepository.save(entrada(produtorA, StatusNotaFiscal.AUTORIZADA, em(2026, 2, 10),
        "FERTILIZANTE NPK 04-14-08", "31052000", "KG", "1000", "2500.00"));
    notaFiscalRepository.save(entrada(produtorA, StatusNotaFiscal.AUTORIZADA, em(2026, 5, 3),
        "Fertilizante foliar", "31059090", "L", "20", "1234.56"));
  }

  @AfterEach
  void tearDown() {
    travas.forEach(CountDownLatch::countDown);
    modelo.reiniciar();
  }

  // --- SSE ---------------------------------------------------------------------------------------

  @Test
  @DisplayName("SSE com a explicacao aceita: rota, resposta, explicacao e fim, nesta ordem")
  void sseComExplicacaoAceita() throws Exception {
    explicacaoResponde(() -> ChatModelSimulado.texto(BOA));

    HttpResponse<String> resposta = stream(PERGUNTA);

    assertThat(resposta.statusCode()).isEqualTo(200);
    List<Evento> eventos = eventos(resposta.body());
    assertThat(eventos).extracting(Evento::nome)
        .containsExactly("rota", "resposta", "explicacao", "fim");
    JsonNode contrato = eventos.get(1).dados();
    assertThat(contrato.path("resposta").asText()).isEqualTo(TEMPLATE);
    // A resposta sai antes da explicacao, sem ela: o numero nao espera o modelo.
    assertThat(contrato.has("explicacao")).isFalse();
    assertThat(eventos.get(2).dados().path("explicacao").asText()).isEqualTo(BOA);
    assertThat(eventos.get(2).dados().size()).isEqualTo(1);
    AssistenteInteracao interacao = interacao();
    assertThat(interacao.getDesfecho()).isEqualTo(DesfechoInteracao.RESPONDIDA);
    assertThat(interacao.getDesfechoExplicacao()).isEqualTo(DesfechoExplicacao.EXPLICACAO_ACEITA);
    assertThat(interacao.getLatenciaExplicacaoMs()).isNotNull().isNotNegative();
    assertThat(interacao.getTokensEntradaExplicacao()).isPositive();
    assertThat(interacao.getTokensSaidaExplicacao()).isPositive();
  }

  @Test
  @DisplayName("SSE com a explicacao descartada: rota, resposta e fim, sem o evento explicacao")
  void sseComExplicacaoDescartada() throws Exception {
    explicacaoResponde(() -> ChatModelSimulado.texto("Você gastou R$ 4.000,00 com fertilizante"
        + " no semestre. Esse total junta as 2 notas de compra do período."));

    List<Evento> eventos = eventos(stream(PERGUNTA).body());

    assertThat(eventos).extracting(Evento::nome).containsExactly("rota", "resposta", "fim");
    assertThat(eventos.get(1).dados().path("resposta").asText()).isEqualTo(TEMPLATE);
    assertThat(interacao().getDesfechoExplicacao())
        .isEqualTo(DesfechoExplicacao.EXPLICACAO_DESCARTADA_NUMERO);
  }

  @Test
  @DisplayName("SSE com a explicacao alem do prazo dela: rota, resposta e fim, e a vaga volta")
  void sseComExplicacaoForaDoPrazo() throws Exception {
    CountDownLatch nunca = trava();
    explicacaoResponde(() -> {
      aguardar(nunca);
      return ChatModelSimulado.texto(BOA);
    });

    long inicio = System.nanoTime();
    List<Evento> eventos = eventos(stream(PERGUNTA).body());

    // O prazo da explicacao (1 s) vence bem antes do da pergunta (4 s).
    assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - inicio)).isLessThan(3_500);
    assertThat(eventos).extracting(Evento::nome).containsExactly("rota", "resposta", "fim");
    assertThat(interacao().getDesfechoExplicacao())
        .isEqualTo(DesfechoExplicacao.EXPLICACAO_DESCARTADA_TEMPO_ESGOTADO);
    // A vaga do produtor voltou: a proxima pergunta e atendida.
    explicacaoResponde(() -> ChatModelSimulado.texto(BOA));
    assertThat(eventos(stream(PERGUNTA).body())).extracting(Evento::nome)
        .containsExactly("rota", "resposta", "explicacao", "fim");
  }

  @Test
  @DisplayName("Erro depois da resposta (prazo da pergunta durante a explicacao): o stream segue "
      + "200, a resposta enviada fica e vem o evento erro, sem 500")
  void erroDepoisDaResposta() throws Exception {
    CountDownLatch liberar = trava();
    explicacaoResponde(() -> {
      // Um provedor que nao responde a interrupcao: passa do prazo da explicacao e do da pergunta.
      aguardarSemInterrupcao(liberar);
      return ChatModelSimulado.texto(BOA);
    });

    HttpResponse<String> resposta = stream(PERGUNTA);
    liberar.countDown();

    assertThat(resposta.statusCode()).isEqualTo(200);
    List<Evento> eventos = eventos(resposta.body());
    assertThat(eventos).extracting(Evento::nome).containsExactly("rota", "resposta", "erro");
    assertThat(eventos.get(1).dados().path("resposta").asText()).isEqualTo(TEMPLATE);
    assertThat(eventos.get(2).dados().path("errors").get(0).path("code").asText())
        .isEqualTo("ASSISTENTE_TEMPO_ESGOTADO");
    assertThat(resposta.body()).doesNotContain(BOA);
    // A pergunta foi auditada como respondida; a explicacao, como fora do prazo.
    AssistenteInteracao interacao = esperar(() -> {
      AssistenteInteracao i = interacao();
      return i.getDesfechoExplicacao() == null ? null : i;
    });
    assertThat(interacao.getDesfecho()).isEqualTo(DesfechoInteracao.RESPONDIDA);
    assertThat(interacao.getDesfechoExplicacao())
        .isEqualTo(DesfechoExplicacao.EXPLICACAO_DESCARTADA_TEMPO_ESGOTADO);
  }

  // --- POST /chat --------------------------------------------------------------------------------

  @Test
  @DisplayName("POST /chat com a explicacao aceita: campo 'explicacao' e os campos de antes iguais")
  void contratoSincronoComExplicacao() throws Exception {
    explicacaoResponde(() -> ChatModelSimulado.texto(BOA));

    HttpResponse<String> resposta = sincrona(PERGUNTA);

    assertThat(resposta.statusCode()).isEqualTo(200);
    JsonNode corpo = JSON.readTree(resposta.body());
    assertThat(corpo.path("resposta").asText()).isEqualTo(TEMPLATE);
    assertThat(corpo.path("explicacao").asText()).isEqualTo(BOA);
    assertThat(campos(corpo)).containsExactlyInAnyOrder("resposta", "rota", "citacoes",
        "fontesDados", "modoFallback", "atalhos", "explicacao");
    assertThat(corpo.path("rota").asText()).isEqualTo("DADOS");
    assertThat(corpo.path("fontesDados").path(0).path("funcao").asText())
        .isEqualTo("totalComprasPorInsumo");
  }

  /** Cada motivo de descarte, forçado pelo modelo simulado: só o template sai. */
  static Stream<Arguments> descartes() {
    String fim = " Esse total junta as 2 notas de compra do período.";
    return Stream.of(
        Arguments.of(DesfechoExplicacao.EXPLICACAO_DESCARTADA_NUMERO,
            (Supplier<ChatResponse>) () -> ChatModelSimulado.texto(
                "Você gastou R$ 4.000,00 com fertilizante." + fim)),
        Arguments.of(DesfechoExplicacao.EXPLICACAO_DESCARTADA_TAMANHO,
            (Supplier<ChatResponse>) () -> ChatModelSimulado.texto(
                "Você comprou fertilizante no semestre.")),
        Arguments.of(DesfechoExplicacao.EXPLICACAO_DESCARTADA_SIGLA,
            (Supplier<ChatResponse>) () -> ChatModelSimulado.texto(
                "O IBS dessas compras de fertilizante entra no mês da nota." + fim)),
        Arguments.of(DesfechoExplicacao.EXPLICACAO_DESCARTADA_CONCLUSAO_LEGAL,
            (Supplier<ChatResponse>) () -> ChatModelSimulado.texto(
                "Essas compras de fertilizante dão direito a crédito de imposto." + fim)),
        Arguments.of(DesfechoExplicacao.EXPLICACAO_DESCARTADA_VAZAMENTO,
            (Supplier<ChatResponse>) () -> ChatModelSimulado.texto(
                "A consulta totalComprasPorInsumo achou as suas compras." + fim)),
        Arguments.of(DesfechoExplicacao.EXPLICACAO_DESCARTADA_CORTADA,
            (Supplier<ChatResponse>) () -> new ChatResponse(List.of(new Generation(
                new AssistantMessage(BOA),
                ChatGenerationMetadata.builder().finishReason("length").build())))),
        Arguments.of(DesfechoExplicacao.EXPLICACAO_DESCARTADA_MODELO_INDISPONIVEL,
            (Supplier<ChatResponse>) () -> {
              throw new IllegalStateException("provedor fora do ar");
            }));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("descartes")
  @DisplayName("POST /chat com a explicacao descartada: so o template, sem o campo, desfecho certo")
  void descarteNoSincrono(DesfechoExplicacao esperado, Supplier<ChatResponse> explicacao)
      throws Exception {
    explicacaoResponde(explicacao);

    HttpResponse<String> resposta = sincrona(PERGUNTA);

    assertThat(resposta.statusCode()).isEqualTo(200);
    JsonNode corpo = JSON.readTree(resposta.body());
    assertThat(corpo.path("resposta").asText()).isEqualTo(TEMPLATE);
    assertThat(corpo.has("explicacao")).isFalse();
    assertThat(corpo.path("modoFallback").asBoolean()).isFalse();
    AssistenteInteracao interacao = interacao();
    assertThat(interacao.getDesfecho()).isEqualTo(DesfechoInteracao.RESPONDIDA);
    assertThat(interacao.getDesfechoExplicacao()).isEqualTo(esperado);
  }

  @Test
  @DisplayName("Fora da rota de dados: nenhuma chamada a mais e a coluna da explicacao nula")
  void foraDaRotaDeDadosNaoExplica() throws Exception {
    modelo.classificarComo("FORA_DE_ESCOPO");

    HttpResponse<String> resposta = sincrona("Quem ganhou o jogo ontem?");

    assertThat(JSON.readTree(resposta.body()).has("explicacao")).isFalse();
    assertThat(modelo.promptsRecebidos()).isEmpty();
    assertThat(interacao().getDesfechoExplicacao()).isNull();
  }

  // --- Cota --------------------------------------------------------------------------------------

  @Test
  @DisplayName("Cota com saldo: a explicacao e cobrada pelo uso real, a parte, sem contar outra "
      + "pergunta, e nada fica reservado")
  void cotaComSaldo() throws Exception {
    explicacaoResponde(() -> ChatModelSimulado.texto(BOA));

    assertThat(JSON.readTree(sincrona(PERGUNTA).body()).path("explicacao").asText())
        .isEqualTo(BOA);

    AssistenteInteracao interacao = interacao();
    UsoSemanalAssistente uso = usoDaSemana();
    assertThat(uso.getCreditosReservados()).isZero();
    assertThat(uso.getPerguntas()).isEqualTo(1);
    assertThat(uso.getTokensEntrada()).isEqualTo(
        (long) interacao.getTokensEntrada() + interacao.getTokensEntradaExplicacao());
    assertThat(uso.getTokensSaida()).isEqualTo(
        (long) interacao.getTokensSaida() + interacao.getTokensSaidaExplicacao());
  }

  @Test
  @DisplayName("Cota sem saldo para a explicacao: o template sai sozinho, 200 e nunca 429")
  void cotaSemSaldoParaAExplicacao() throws Exception {
    // Durante a escolha das funcoes, outro gasto deixa a semana com menos que o teto da explicacao.
    modelo.responderCom(prompt -> {
      if (ehExplicacao(prompt)) {
        return ChatModelSimulado.texto(BOA);
      }
      jdbc.update("UPDATE assistente_uso_semanal SET creditos_consumidos = ? WHERE titular_id = ?",
          1_000_000 - 5_000, produtorA.getId());
      return funcaoDeCompras();
    });

    HttpResponse<String> resposta = sincrona(PERGUNTA);

    assertThat(resposta.statusCode()).isEqualTo(200);
    JsonNode corpo = JSON.readTree(resposta.body());
    assertThat(corpo.path("resposta").asText()).isEqualTo(TEMPLATE);
    assertThat(corpo.has("explicacao")).isFalse();
    assertThat(modelo.promptsRecebidos()).noneMatch(AssistenteExplicacaoIntegrationTest::ehExplicacao);
    assertThat(interacao().getDesfechoExplicacao())
        .isEqualTo(DesfechoExplicacao.EXPLICACAO_DESCARTADA_SEM_SALDO);
    assertThat(usoDaSemana().getCreditosReservados()).isZero();
  }

  // ----------------------------------------------------------------------------------------------

  /** A escolha das funções pede as compras; a explicação responde como o fornecedor manda. */
  private void explicacaoResponde(Supplier<ChatResponse> explicacao) {
    modelo.responderCom(prompt -> ehExplicacao(prompt) ? explicacao.get() : funcaoDeCompras());
  }

  private static ChatResponse funcaoDeCompras() {
    return ChatModelSimulado.funcao("totalComprasPorInsumo", Map.of(
        "descricaoOuNcm", "fertilizante", "dataInicio", "2026-01-01", "dataFim", "2026-06-30"));
  }

  private static boolean ehExplicacao(Prompt prompt) {
    return prompt.getSystemMessage() != null
        && PromptExplicacao.SISTEMA.equals(prompt.getSystemMessage().getText());
  }

  private UsoSemanalAssistente usoDaSemana() {
    return usoRepository.findAll().stream()
        .filter(u -> u.getId().getTitularTipo() == TipoTitularCota.PRODUTOR
            && u.getId().getTitularId().equals(produtorA.getId()))
        .findFirst().orElseThrow();
  }

  private AssistenteInteracao interacao() {
    List<AssistenteInteracao> interacoes =
        interacaoRepository.findByProdutorIdOrderByCriadoEmDesc(produtorA.getId());
    assertThat(interacoes).isNotEmpty();
    return interacoes.get(0);
  }

  private static <T> T esperar(Supplier<T> condicao) throws InterruptedException {
    long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < limite) {
      T valor = condicao.get();
      if (valor != null) {
        return valor;
      }
      Thread.sleep(50);
    }
    throw new AssertionError("Condicao nao atingida em 10 s");
  }

  private CountDownLatch trava() {
    CountDownLatch trava = new CountDownLatch(1);
    travas.add(trava);
    return trava;
  }

  /** O modelo "pensando": bloqueia até liberar ou até a thread da Ceres ser interrompida. */
  private static void aguardar(CountDownLatch liberar) {
    try {
      liberar.await(30, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrompida", e);
    }
  }

  /** O modelo que não responde à interrupção: só para quando liberado (ou em 15 s). */
  private static void aguardarSemInterrupcao(CountDownLatch liberar) {
    long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (liberar.getCount() > 0 && System.nanoTime() < limite) {
      try {
        liberar.await(50, TimeUnit.MILLISECONDS);
      } catch (InterruptedException e) {
        // segue esperando
      }
    }
  }

  private static List<String> campos(JsonNode corpo) {
    List<String> nomes = new ArrayList<>();
    corpo.fieldNames().forEachRemaining(nomes::add);
    return nomes;
  }

  private HttpResponse<String> sincrona(String pergunta) throws Exception {
    return http.send(requisicao("/api/assistente/chat", "application/json", pergunta),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> stream(String pergunta) throws Exception {
    return http.send(requisicao("/api/assistente/chat/stream", "text/event-stream", pergunta),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpRequest requisicao(String caminho, String accept, String pergunta)
      throws Exception {
    return HttpRequest.newBuilder(URI.create("http://localhost:" + porta + caminho))
        .timeout(Duration.ofSeconds(20))
        .header("Content-Type", "application/json")
        .header("Accept", accept)
        .header(MockIdentidadeFilter.HEADER_USUARIO_ID, produtorA.getId().toString())
        .POST(HttpRequest.BodyPublishers.ofString(
            JSON.writeValueAsString(Map.of("pergunta", pergunta))))
        .build();
  }

  /** Eventos do corpo {@code text/event-stream}; comentário não é evento. */
  private static List<Evento> eventos(String corpo) throws Exception {
    List<Evento> eventos = new ArrayList<>();
    for (String bloco : corpo.replace("\r\n", "\n").split("\n\n")) {
      String nome = null;
      List<String> dados = new ArrayList<>();
      for (String linha : bloco.split("\n")) {
        if (linha.startsWith("event:")) {
          nome = linha.substring("event:".length()).strip();
        } else if (linha.startsWith("data:")) {
          dados.add(linha.substring("data:".length()));
        }
      }
      if (nome != null) {
        String json = dados.stream().collect(Collectors.joining());
        assertThat(json).as("dados do evento %s (corpo: %s)", nome, corpo).isNotBlank();
        eventos.add(new Evento(nome, JSON.readTree(json)));
      }
    }
    return eventos;
  }

  private record Evento(String nome, JsonNode dados) { }
}
