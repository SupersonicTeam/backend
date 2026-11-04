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
import jakarta.servlet.Filter;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.web.util.ServletRequestPathUtils;

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
@Import({TestDatabaseCleaner.class, IdentidadeMockadaConfig.class,
    AssistenteStreamingIntegrationTest.FiltroDaCorrida.class})
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
class AssistenteStreamingIntegrationTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** Limite de cada trava da corrida (#335): a coreografia nao pode pendurar a suite. */
  private static final long LIMITE_TRAVA_S = 10;

  /** Coreografia da corrida do teste #335; nula nos outros testes (o filtro so repassa). */
  private static volatile Coreografia coreografia;

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
    Coreografia c = coreografia;
    coreografia = null;
    if (c != null) {
      c.soltarTudo();
    }
    modelo.reiniciar();
  }

  @Test
  @DisplayName("SSE: eventos rota, resposta e fim, nesta ordem, e a rota vai para a auditoria")
  void eventosNaOrdem() throws Exception {
    modelo.classificarComo("DADOS");
    modelo.responderCom(prompt -> ChatModelSimulado.funcao("totalComprasPorInsumo", java.util.Map.of(
        "descricaoOuNcm", "fertilizante", "dataInicio", "2026-01-01", "dataFim", "2026-06-30")));

    HttpResponse<String> resposta = stream(produtorA, "Quanto comprei de fertilizante?");

    assertThat(resposta.statusCode()).isEqualTo(200);
    assertThat(resposta.headers().firstValue("Content-Type")).get().asString()
        .startsWith("text/event-stream");
    List<Evento> eventos = eventos(resposta.body());
    assertThat(eventos).extracting(Evento::nome).containsExactly("rota", "resposta", "fim");
    assertThat(eventos.get(0).dados().path("rota").asText()).isEqualTo("DADOS");
    JsonNode contrato = eventos.get(1).dados();
    assertThat(contrato.path("resposta").asText())
        .isEqualTo("Nao encontrei compras de fertilizante entre 01/01/2026 e 30/06/2026.");
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
    assertThat(eventos(streamQuandoHouverVaga(produtorA, "de novo").body()))
        .extracting(Evento::nome).containsExactly("rota", "resposta", "fim");
  }

  /**
   * A corrida da #335 de forma determinística. No primeiro commit, o {@code HeaderWriterFilter}
   * roda os escritores de cabeçalho na thread que fez o commit; o da CSP lê o caminho analisado
   * em dois passos ({@code has}, depois {@code get} com {@code Assert.notNull}), e o
   * {@code finally} do {@code DispatcherServlet.doService} remove esse atributo mesmo com o async
   * iniciado. Quando o commit era da Ceres e a remoção caía entre os dois passos, o envio da
   * {@code rota} falhava depois de escrever {@code event:rota} e {@code data:} vazio, e o stream
   * fechava sem {@code resposta}, {@code fim} nem {@code erro}. O {@link FiltroDaCorrida} só
   * segura e solta as duas threads nessa ordem (travas, sem {@code sleep}); com o commit na thread
   * da requisição, a Ceres nunca roda os escritores e a ordem forçada não muda nada.
   */
  @Test
  @DisplayName("#335: rota enviada pela Ceres enquanto o DispatcherServlet remove o caminho "
      + "analisado: o stream segue com resposta e fim")
  void envioDaCeresDuranteASaidaDoDispatcher() throws Exception {
    Coreografia c = new Coreografia();
    coreografia = c;
    // A classificacao (primeira chamada ao modelo) so volta quando a thread da requisicao ja
    // esta no finally do DispatcherServlet: a rota sai depois de o Spring assumir o emissor.
    modelo.responderCom(prompt -> {
      esperarTrava(c.requisicaoNaSaida);
      return ChatModelSimulado.texto("{\"intencao\": \"FORA_DE_ESCOPO\", \"confianca\": 0.95}");
    });

    HttpResponse<String> resposta = stream(produtorA, "Quem ganhou o jogo ontem?");

    assertThat(c.requisicaoNaSaida.getCount()).as("a coreografia aconteceu").isZero();
    assertThat(resposta.statusCode()).isEqualTo(200);
    List<Evento> eventos = eventos(resposta.body());
    assertThat(eventos).extracting(Evento::nome).containsExactly("rota", "resposta", "fim");
    assertThat(eventos.get(0).dados().path("rota").asText()).isEqualTo("FORA_DE_ESCOPO");
    assertThat(interacaoRepository.findByProdutorIdOrderByCriadoEmDesc(produtorA.getId()))
        .singleElement()
        .satisfies(i -> assertThat(i.getDesfecho()).isEqualTo(DesfechoInteracao.FORA_DE_ESCOPO));
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

  /**
   * Stream de uma nova pergunta assim que a vaga do produtor voltar. O erro de tempo esgotado sai
   * pelo prazo, antes de a thread da Ceres terminar: ela ainda é interrompida, audita e só então
   * devolve a vaga. Pergunta nesse intervalo recebe, com razão, 503 {@code ASSISTENTE_OCUPADO}
   * (a auditoria já gravada não quer dizer vaga livre — #335).
   */
  private HttpResponse<String> streamQuandoHouverVaga(Produtor produtor, String pergunta)
      throws Exception {
    long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    // Cliente novo (a conexao do stream abortado pode ter sido fechada pelo Tomcat), um so para
    // as tentativas: o 503 e resposta comum e a conexao dele pode ser reaproveitada.
    try (HttpClient novo = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
      while (true) {
        HttpResponse<String> resposta = novo.send(requisicao(produtor.getId(),
            "/api/assistente/chat/stream", "text/event-stream", pergunta),
            HttpResponse.BodyHandlers.ofString());
        if (resposta.statusCode() != 503 || System.nanoTime() > limite) {
          return resposta;
        }
        assertOcupado(resposta, "pergunta anterior");
        // Consulta a condicao (a vaga) a cada 50 ms, como o esperarInteracao.
        Thread.sleep(50);
      }
    }
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

  /** As travas de uma execução. */
  static final class Coreografia {
    /** A thread da requisição chegou à remoção do caminho analisado, já com o async iniciado. */
    final CountDownLatch requisicaoNaSaida = new CountDownLatch(1);
    /** A Ceres já leu o atributo uma vez (o {@code has}) ou já escreveu na resposta. */
    final CountDownLatch podeRemover = new CountDownLatch(1);
    /** A remoção aconteceu. */
    final CountDownLatch removido = new CountDownLatch(1);
    final AtomicBoolean primeiraLeituraDaCeres = new AtomicBoolean();

    void soltarTudo() {
      requisicaoNaSaida.countDown();
      podeRemover.countDown();
      removido.countDown();
    }
  }

  @TestConfiguration
  static class FiltroDaCorrida {

    @Bean
    FilterRegistrationBean<Filter> filtroDaCorrida() {
      FilterRegistrationBean<Filter> registro = new FilterRegistrationBean<>((req, res, chain) -> {
        Coreografia c = coreografia;
        if (c == null) {
          chain.doFilter(req, res);
          return;
        }
        chain.doFilter(new RequisicaoDaCorrida((HttpServletRequest) req, c),
            new RespostaDaCorrida((HttpServletResponse) res, c));
      });
      registro.addUrlPatterns("/api/assistente/chat/stream");
      // Por fora do Spring Security: enxerga as leituras do atributo feitas pelos escritores de
      // cabecalho e a remocao feita pelo DispatcherServlet.
      registro.setOrder(Ordered.HIGHEST_PRECEDENCE);
      return registro;
    }
  }

  private static boolean threadDaCeres() {
    String nome = Thread.currentThread().getName();
    return nome.startsWith("agro-ceres-") && !nome.endsWith("prazo");
  }

  /** Segura a remoção do caminho analisado até a Ceres ter lido o atributo uma vez. */
  private static final class RequisicaoDaCorrida extends HttpServletRequestWrapper {

    private final Coreografia c;

    RequisicaoDaCorrida(HttpServletRequest request, Coreografia c) {
      super(request);
      this.c = c;
    }

    @Override
    public Object getAttribute(String name) {
      if (ServletRequestPathUtils.PATH_ATTRIBUTE.equals(name) && threadDaCeres()
          && c.primeiraLeituraDaCeres.compareAndSet(false, true)) {
        // O has() do matcher: a remocao acontece agora, antes do get().
        Object valor = super.getAttribute(name);
        c.podeRemover.countDown();
        esperarTrava(c.removido);
        return valor;
      }
      return super.getAttribute(name);
    }

    @Override
    public void removeAttribute(String name) {
      if (ServletRequestPathUtils.PATH_ATTRIBUTE.equals(name) && !threadDaCeres()
          && isAsyncStarted()) {
        c.requisicaoNaSaida.countDown();
        esperarTrava(c.podeRemover);
        super.removeAttribute(name);
        c.removido.countDown();
        return;
      }
      super.removeAttribute(name);
    }
  }

  /** Avisa quando a Ceres faz flush na resposta: sem ler o atributo, a remoção pode seguir. */
  private static final class RespostaDaCorrida extends HttpServletResponseWrapper {

    private final Coreografia c;
    private ServletOutputStream saida;

    RespostaDaCorrida(HttpServletResponse response, Coreografia c) {
      super(response);
      this.c = c;
    }

    @Override
    public synchronized ServletOutputStream getOutputStream() throws IOException {
      if (saida == null) {
        ServletOutputStream original = super.getOutputStream();
        saida = new ServletOutputStream() {
          // So o flush avisa: no commit, o HeaderWriterFilter roda os escritores de cabecalho
          // ANTES de repassar o flush, e a escrita anterior a ele ainda nao chegou a eles.
          @Override
          public void write(int b) throws IOException {
            original.write(b);
          }

          @Override
          public void write(byte[] b, int off, int len) throws IOException {
            original.write(b, off, len);
          }

          @Override
          public void flush() throws IOException {
            avisar();
            original.flush();
          }

          @Override
          public void close() throws IOException {
            original.close();
          }

          @Override
          public boolean isReady() {
            return original.isReady();
          }

          @Override
          public void setWriteListener(WriteListener listener) {
            original.setWriteListener(listener);
          }
        };
      }
      return saida;
    }

    @Override
    public void flushBuffer() throws IOException {
      avisar();
      super.flushBuffer();
    }

    private void avisar() {
      if (threadDaCeres()) {
        c.podeRemover.countDown();
      }
    }
  }

  private static void esperarTrava(CountDownLatch trava) {
    try {
      trava.await(LIMITE_TRAVA_S, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Lê o corpo {@code text/event-stream}: blocos separados por linha em branco; comentário (linha
   * com {@code :}) não é evento. Evento sem dados é falha, não nó vazio: foi assim que a corrida da
   * #335 aparecia só como {@code ["rota"]}.
   */
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
