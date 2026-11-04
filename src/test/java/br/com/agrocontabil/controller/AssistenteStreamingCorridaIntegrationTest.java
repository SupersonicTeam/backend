package br.com.agrocontabil.controller;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.config.MockIdentidadeFilter;
import br.com.agrocontabil.model.DesfechoInteracao;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.repository.AssistenteInteracaoRepository;
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
import java.util.Map;
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
 * A corrida da issue #335 de forma determinística: o primeiro envio da Ceres acontece enquanto a
 * thread da requisição ainda sai do {@code DispatcherServlet}.
 *
 * <p>No primeiro commit da resposta, o {@code HeaderWriterFilter} do Spring Security roda os
 * escritores de cabeçalho na thread que fez o commit. O da CSP ({@code /api/**}) usa um
 * {@code PathPatternRequestMatcher}, que lê o atributo do caminho já analisado em dois passos
 * ({@code has}, depois {@code get} com {@code Assert.notNull}). Ao mesmo tempo, o
 * {@code finally} do {@code DispatcherServlet.doService} remove esse atributo — mesmo com o async
 * iniciado. Quando o commit era da thread da Ceres e a remoção caía entre os dois passos, o envio
 * do evento {@code rota} falhava com {@code IllegalArgumentException} depois de escrever
 * {@code event:rota\ndata:}: a pergunta virava {@code CANCELADA} e o stream fechava sem
 * {@code resposta}, {@code fim} nem {@code erro} — o chat parado no meio.
 *
 * <p>Um filtro de teste, por fora de tudo, só segura e solta as duas threads na ordem da falha
 * (travas, sem {@code sleep}). Com o commit feito pela thread da requisição, a Ceres nunca roda os
 * escritores de cabeçalho e a ordem forçada não muda nada.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "app.assistente.max-conversas-simultaneas=2",
    "app.assistente.timeout-pergunta=10s",
    "app.assistente.timeout-funcao=1s"})
@Import({TestDatabaseCleaner.class, IdentidadeMockadaConfig.class,
    AssistenteStreamingCorridaIntegrationTest.FiltroDaCorrida.class})
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
class AssistenteStreamingCorridaIntegrationTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** Limite de cada trava: a coreografia não pode pendurar a suíte se algo mudar. */
  private static final long LIMITE_TRAVA_S = 10;

  private static volatile Coreografia coreografia;

  @LocalServerPort private int porta;
  @Autowired private ProdutorRepository produtorRepository;
  @Autowired private AssistenteInteracaoRepository interacaoRepository;
  @Autowired private ChatModelSimulado modelo;
  @Autowired private TestDatabaseCleaner cleaner;
  private Produtor produtor;

  @BeforeEach
  void setUp() {
    cleaner.limparTudo();
    modelo.reiniciar();
    produtor = produtorRepository.save(Produtor.builder()
        .nomeCompleto("Fazenda A").cpfCnpj("98765432100").estado("MT").build());
  }

  @AfterEach
  void tearDown() {
    Coreografia c = coreografia;
    coreografia = null;
    if (c != null) {
      c.soltarTudo();
    }
    modelo.reiniciar();
  }

  @Test
  @DisplayName("#335: rota enviada pela Ceres enquanto o DispatcherServlet remove o caminho "
      + "analisado: o stream segue com resposta e fim")
  void envioDaCeresDuranteASaidaDoDispatcher() throws Exception {
    Coreografia c = new Coreografia();
    coreografia = c;
    // A classificacao (primeira chamada ao modelo) so volta quando a thread da requisicao ja
    // esta no finally do DispatcherServlet: a rota sai depois de o Spring assumir o emissor.
    modelo.responderCom(prompt -> {
      aguardar(c.requisicaoNaSaida);
      return ChatModelSimulado.texto("{\"intencao\": \"FORA_DE_ESCOPO\", \"confianca\": 0.95}");
    });

    HttpResponse<String> resposta = stream("Quem ganhou o jogo ontem?");

    assertThat(c.requisicaoNaSaida.getCount()).as("a coreografia aconteceu").isZero();
    assertThat(resposta.statusCode()).isEqualTo(200);
    List<Evento> eventos = eventos(resposta.body());
    assertThat(eventos).extracting(Evento::nome).containsExactly("rota", "resposta", "fim");
    assertThat(eventos.get(0).dados().path("rota").asText()).isEqualTo("FORA_DE_ESCOPO");
    assertThat(interacaoRepository.findByProdutorIdOrderByCriadoEmDesc(produtor.getId()))
        .singleElement()
        .satisfies(i -> assertThat(i.getDesfecho()).isEqualTo(DesfechoInteracao.FORA_DE_ESCOPO));
  }

  // ----------------------------------------------------------------------------------------------

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
        aguardar(c.removido);
        return valor;
      }
      return super.getAttribute(name);
    }

    @Override
    public void removeAttribute(String name) {
      if (ServletRequestPathUtils.PATH_ATTRIBUTE.equals(name) && !threadDaCeres()
          && isAsyncStarted()) {
        c.requisicaoNaSaida.countDown();
        aguardar(c.podeRemover);
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

  private static void aguardar(CountDownLatch trava) {
    try {
      trava.await(LIMITE_TRAVA_S, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private HttpResponse<String> stream(String pergunta) throws Exception {
    HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    String corpo = JSON.writeValueAsString(Map.of("pergunta", pergunta));
    return http.send(HttpRequest.newBuilder(
            URI.create("http://localhost:" + porta + "/api/assistente/chat/stream"))
        .timeout(Duration.ofSeconds(30))
        .header("Content-Type", "application/json")
        .header("Accept", "text/event-stream")
        .header(MockIdentidadeFilter.HEADER_USUARIO_ID, produtor.getId().toString())
        .POST(HttpRequest.BodyPublishers.ofString(corpo))
        .build(), HttpResponse.BodyHandlers.ofString());
  }

  /** Lê o corpo {@code text/event-stream}; evento sem dados é falha, não nó vazio. */
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
