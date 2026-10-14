package br.com.agrocontabil.controller;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.assistente.PromptsCeres;
import br.com.agrocontabil.config.MockIdentidadeFilter;
import br.com.agrocontabil.model.AssistenteInteracao;
import br.com.agrocontabil.model.Contador;
import br.com.agrocontabil.model.DesfechoInteracao;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.repository.AssistenteInteracaoRepository;
import br.com.agrocontabil.repository.ContadorRepository;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;

/**
 * Streaming (SSE) e isolamento de recursos da Ceres de ponta a ponta, com servidor real (issue
 * #157).
 *
 * <p>Servidor de verdade, e não MockMvc, de propósito: o que interessa é o dispatch assíncrono
 * passando pela cadeia de segurança, os eventos chegando pela rede na ordem certa e os timeouts
 * reais do container. A identidade vem do header {@code X-Usuario-Id}, pelo
 * {@link IdentidadeMockadaConfig}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "app.assistente.max-conversas-simultaneas=2",
    "app.assistente.timeout-pergunta=3s",
    "app.assistente.timeout-funcao=1s"})
@Import({TestDatabaseCleaner.class, IdentidadeMockadaConfig.class})
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
class AssistenteStreamingIntegrationTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @LocalServerPort private int porta;
  @Autowired private ProdutorRepository produtorRepository;
  @Autowired private ContadorRepository contadorRepository;
  @Autowired private AssistenteInteracaoRepository interacaoRepository;
  @Autowired private ChatModelSimulado modelo;
  @Autowired private TestDatabaseCleaner cleaner;

  private final HttpClient http = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(5)).build();
  private final List<CountDownLatch> travas = new ArrayList<>();
  private Produtor produtorA;
  private Produtor produtorB;
  private Produtor produtorC;

  @BeforeEach
  void setUp() {
    cleaner.limparTudo();
    modelo.reiniciar();
    produtorA = produtorRepository.save(Produtor.builder()
        .nomeCompleto("Fazenda A").cpfCnpj("98765432100").estado("MT").build());
    produtorB = produtorRepository.save(Produtor.builder()
        .nomeCompleto("Fazenda B").cpfCnpj("11122233344").estado("PR").build());
    produtorC = produtorRepository.save(Produtor.builder()
        .nomeCompleto("Fazenda C").cpfCnpj("22233344455").estado("GO").build());
  }

  @AfterEach
  void tearDown() {
    // Nenhuma pergunta fica presa para o proximo teste.
    travas.forEach(CountDownLatch::countDown);
    modelo.reiniciar();
  }

  @Test
  @DisplayName("SSE: eventos rota, resposta e fim, nesta ordem, e a rota vai para a auditoria")
  void eventosNaOrdem() throws Exception {
    modelo.classificarComo("DADOS");
    modelo.responderTexto("Nao encontrei notas de fertilizante no periodo.");

    HttpResponse<String> resposta = stream(produtorA, "Quanto comprei de fertilizante?");

    assertThat(resposta.statusCode()).isEqualTo(200);
    assertThat(resposta.headers().firstValue("Content-Type")).get().asString()
        .startsWith("text/event-stream");
    List<Evento> eventos = eventos(resposta.body());
    assertThat(eventos).extracting(Evento::nome).containsExactly("rota", "resposta", "fim");
    assertThat(eventos.get(0).dados().path("rota").asText()).isEqualTo("DADOS");
    JsonNode contrato = eventos.get(1).dados();
    assertThat(contrato.path("resposta").asText())
        .isEqualTo("Nao encontrei notas de fertilizante no periodo.");
    assertThat(contrato.path("rota").asText()).isEqualTo("DADOS");
    assertThat(contrato.path("modoFallback").asBoolean()).isFalse();
    assertThat(contrato.has("citacoes")).isTrue();
    assertThat(contrato.has("fontesDados")).isTrue();

    assertThat(interacaoRepository.findByProdutorIdOrderByCriadoEmDesc(produtorA.getId()))
        .singleElement()
        .satisfies(i -> {
          assertThat(i.getRota()).isEqualTo("DADOS");
          assertThat(i.getDesfecho()).isEqualTo(DesfechoInteracao.RESPONDIDA);
        });
  }

  @Test
  @DisplayName("FORA_DE_ESCOPO: resposta fixa, sem nenhuma chamada ao modelo alem da classificacao")
  void foraDeEscopoSemModelo() throws Exception {
    modelo.classificarComo("FORA_DE_ESCOPO");

    List<Evento> eventos = eventos(stream(produtorA, "Quem ganhou o jogo ontem?").body());

    assertThat(eventos).extracting(Evento::nome).containsExactly("rota", "resposta", "fim");
    assertThat(eventos.get(1).dados().path("resposta").asText())
        .isEqualTo(PromptsCeres.FORA_DE_ESCOPO);
    assertThat(eventos.get(1).dados().path("fontesDados")).isEmpty();
    assertThat(modelo.classificacoesRecebidas()).hasSize(1);
    assertThat(modelo.promptsRecebidos()).isEmpty();
    assertThat(interacaoRepository.findByProdutorIdOrderByCriadoEmDesc(produtorA.getId()))
        .singleElement()
        .satisfies(i -> assertThat(i.getDesfecho()).isEqualTo(DesfechoInteracao.FORA_DE_ESCOPO));
  }

  @Test
  @DisplayName("#146: modelo fora na pergunta de dados: rota, resposta em fallback e fim")
  void fallbackNoStreaming() throws Exception {
    modelo.falharCom(new IllegalStateException("connection refused: ollama:11434 segredo"));

    HttpResponse<String> resposta = stream(produtorA, "Qual o meu saldo de IBS?");

    assertThat(resposta.statusCode()).isEqualTo(200);
    List<Evento> eventos = eventos(resposta.body());
    assertThat(eventos).extracting(Evento::nome).containsExactly("rota", "resposta", "fim");
    // Sem o classificador, a intencao veio das palavras-chave ("meu" = dados do produtor).
    assertThat(eventos.get(0).dados().path("rota").asText()).isEqualTo("DADOS");
    JsonNode contrato = eventos.get(1).dados();
    assertThat(contrato.path("modoFallback").asBoolean()).isTrue();
    assertThat(contrato.path("atalhos").get(0).asText()).isEqualTo("APURACAO");
    assertThat(resposta.body()).doesNotContain("ollama").doesNotContain("segredo");
    assertThat(interacaoRepository.findByProdutorIdOrderByCriadoEmDesc(produtorA.getId()))
        .singleElement()
        .satisfies(i -> assertThat(i.getDesfecho()).isEqualTo(DesfechoInteracao.MODO_FALLBACK));
  }

  @Test
  @DisplayName("Modelo e base fora: evento erro com o envelope errors[], sem detalhe tecnico")
  void eventoDeErro() throws Exception {
    modelo.falharCom(new IllegalStateException("connection refused: ollama:11434 segredo"));

    // Pergunta de lei: o fallback precisa da busca textual, e o H2 do profile test nao tem a
    // tabela de legislacao — faz o papel da base fora do ar.
    HttpResponse<String> resposta =
        stream(produtorA, "O que diz a lei sobre a reducao de aliquota?");

    assertThat(resposta.statusCode()).isEqualTo(200);
    List<Evento> eventos = eventos(resposta.body());
    // A rota sai antes (intencao por palavras-chave); a busca textual falha depois dela.
    assertThat(eventos).extracting(Evento::nome).containsExactly("rota", "erro");
    JsonNode erro = eventos.get(1).dados();
    assertThat(erro.path("status").asInt()).isEqualTo(503);
    assertThat(erro.path("path").asText()).isEqualTo("/api/assistente/chat/stream");
    assertThat(erro.path("errors").get(0).path("code").asText())
        .isEqualTo("ASSISTENTE_INDISPONIVEL");
    assertThat(resposta.body()).doesNotContain("ollama").doesNotContain("segredo")
        .doesNotContain("IllegalStateException").doesNotContain("legislacao_trecho");
    assertThat(interacaoRepository.findByProdutorIdOrderByCriadoEmDesc(produtorA.getId()))
        .singleElement()
        .satisfies(i -> {
          assertThat(i.getRota()).isEqualTo("LEGISLACAO");
          assertThat(i.getDesfecho()).isEqualTo(DesfechoInteracao.MODELO_INDISPONIVEL);
        });
  }

  @Test
  @DisplayName("Contador: 403 antes de o stream abrir, no envelope errors[]")
  void contadorBarradoAntesDoStream() throws Exception {
    Contador contador = contadorRepository.save(Contador.builder()
        .nomeCompleto("Escritorio").cpfCnpj("12.345.678/0001-95").build());

    HttpResponse<String> resposta = enviar(contador.getId(), "/api/assistente/chat/stream",
        "text/event-stream", "Qual o saldo?");

    assertThat(resposta.statusCode()).isEqualTo(403);
    assertThat(JSON.readTree(resposta.body()).path("errors").get(0).path("code").asText())
        .isEqualTo("PAPEL_NAO_AUTORIZADO");
    assertThat(modelo.classificacoesRecebidas()).isEmpty();
    assertThat(modelo.promptsRecebidos()).isEmpty();
  }

  @Test
  @DisplayName("Pergunta vazia no streaming: 400 em JSON antes de o stream abrir (nao 500)")
  void validacaoAntesDoStream() throws Exception {
    HttpResponse<String> resposta = stream(produtorA, "");

    assertThat(resposta.statusCode()).isEqualTo(400);
    assertThat(resposta.headers().firstValue("Content-Type")).get().asString()
        .startsWith("application/json");
    assertThat(JSON.readTree(resposta.body()).path("errors").get(0).path("field").asText())
        .isEqualTo("pergunta");
  }

  @Test
  @DisplayName("Bulkhead: segunda pergunta do mesmo produtor e pergunta acima do limite global "
      + "recebem 503 ASSISTENTE_OCUPADO com Retry-After, sincrono e streaming")
  void bulkhead() throws Exception {
    modelo.classificarComo("DADOS");
    CountDownLatch chegaram = new CountDownLatch(2);
    CountDownLatch liberar = trava();
    modelo.responderCom(prompt -> {
      chegaram.countDown();
      aguardar(liberar);
      return ChatModelSimulado.texto("Sem notas no periodo.");
    });

    // A e B ocupam as duas vagas globais.
    CompletableFuture<HttpResponse<String>> perguntaA = sincronaAsync(produtorA, "pergunta A");
    CompletableFuture<HttpResponse<String>> perguntaB = sincronaAsync(produtorB, "pergunta B");
    assertThat(chegaram.await(10, TimeUnit.SECONDS)).isTrue();

    // Mesmo produtor A de novo, pelos dois caminhos.
    assertOcupado(sincrona(produtorA, "de novo"), "pergunta anterior");
    assertOcupado(stream(produtorA, "de novo"), "pergunta anterior");
    // Produtor C, sem vaga global.
    assertOcupado(sincrona(produtorC, "outra"), "muitas perguntas");

    liberar.countDown();
    assertThat(perguntaA.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
    assertThat(perguntaB.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
    // Vagas devolvidas: C e atendido.
    modelo.responderTexto("Sem notas no periodo.");
    assertThat(sincrona(produtorC, "outra").statusCode()).isEqualTo(200);
  }

  @Test
  @DisplayName("Tempo total esgotado (sincrono): 503 ASSISTENTE_TEMPO_ESGOTADO e auditoria")
  void tempoEsgotadoSincrono() throws Exception {
    modelo.classificarComo("DADOS");
    CountDownLatch liberar = trava();
    modelo.responderCom(prompt -> {
      aguardar(liberar);
      return ChatModelSimulado.texto("tarde demais");
    });

    HttpResponse<String> resposta = sincrona(produtorA, "pergunta lenta");

    assertThat(resposta.statusCode()).isEqualTo(503);
    assertThat(JSON.readTree(resposta.body()).path("errors").get(0).path("code").asText())
        .isEqualTo("ASSISTENTE_TEMPO_ESGOTADO");
    assertThat(esperarInteracao(produtorA).getDesfecho())
        .isEqualTo(DesfechoInteracao.TEMPO_ESGOTADO);
  }

  @Test
  @DisplayName("Tempo total esgotado (streaming): evento erro ASSISTENTE_TEMPO_ESGOTADO e a vaga "
      + "volta")
  void tempoEsgotadoStreaming() throws Exception {
    modelo.classificarComo("DADOS");
    CountDownLatch liberar = trava();
    modelo.responderCom(prompt -> {
      aguardar(liberar);
      return ChatModelSimulado.texto("tarde demais");
    });

    HttpResponse<String> resposta = stream(produtorA, "pergunta lenta");

    List<Evento> eventos = eventos(resposta.body());
    assertThat(eventos).extracting(Evento::nome).containsExactly("rota", "erro");
    assertThat(eventos.get(1).dados().path("errors").get(0).path("code").asText())
        .isEqualTo("ASSISTENTE_TEMPO_ESGOTADO");
    assertThat(esperarInteracao(produtorA).getDesfecho())
        .isEqualTo(DesfechoInteracao.TEMPO_ESGOTADO);

    // A thread da Ceres foi interrompida e devolveu a vaga do produtor.
    modelo.responderTexto("Sem notas no periodo.");
    assertThat(eventos(stream(produtorA, "de novo").body())).extracting(Evento::nome)
        .containsExactly("rota", "resposta", "fim");
  }

  // ----------------------------------------------------------------------------------------------

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

  private AssistenteInteracao esperarInteracao(Produtor produtor) throws InterruptedException {
    long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < limite) {
      List<AssistenteInteracao> interacoes =
          interacaoRepository.findByProdutorIdOrderByCriadoEmDesc(produtor.getId());
      if (!interacoes.isEmpty()) {
        return interacoes.get(0);
      }
      Thread.sleep(50);
    }
    throw new AssertionError("Nenhuma interacao auditada para o produtor");
  }

  private static void assertOcupado(HttpResponse<String> resposta, String trechoMensagem)
      throws Exception {
    assertThat(resposta.statusCode()).isEqualTo(503);
    assertThat(resposta.headers().firstValue("Retry-After")).contains("5");
    JsonNode erro = JSON.readTree(resposta.body()).path("errors").get(0);
    assertThat(erro.path("code").asText()).isEqualTo("ASSISTENTE_OCUPADO");
    assertThat(erro.path("message").asText()).containsIgnoringCase(trechoMensagem);
  }

  private HttpResponse<String> sincrona(Produtor produtor, String pergunta) throws Exception {
    return enviar(produtor.getId(), "/api/assistente/chat", "application/json", pergunta);
  }

  private CompletableFuture<HttpResponse<String>> sincronaAsync(
      Produtor produtor, String pergunta) {
    return http.sendAsync(requisicao(produtor.getId(), "/api/assistente/chat", "application/json",
        pergunta), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> stream(Produtor produtor, String pergunta) throws Exception {
    return enviar(produtor.getId(), "/api/assistente/chat/stream", "text/event-stream", pergunta);
  }

  private HttpResponse<String> enviar(UUID usuario, String caminho, String accept,
      String pergunta) throws Exception {
    return http.send(requisicao(usuario, caminho, accept, pergunta),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpRequest requisicao(UUID usuario, String caminho, String accept, String pergunta) {
    try {
      String corpo = JSON.writeValueAsString(java.util.Map.of("pergunta", pergunta));
      return HttpRequest.newBuilder(URI.create("http://localhost:" + porta + caminho))
          .timeout(Duration.ofSeconds(20))
          .header("Content-Type", "application/json")
          .header("Accept", accept)
          .header(MockIdentidadeFilter.HEADER_USUARIO_ID, usuario.toString())
          .POST(HttpRequest.BodyPublishers.ofString(corpo))
          .build();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** Lê o corpo {@code text/event-stream}: blocos separados por linha em branco. */
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
        eventos.add(new Evento(nome, JSON.readTree(dados.stream().collect(Collectors.joining()))));
      }
    }
    return eventos;
  }

  private record Evento(String nome, JsonNode dados) { }
}
