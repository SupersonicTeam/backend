package br.com.agrocontabil.assistente.llm;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.config.AssistenteProperties;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

/**
 * Testes do {@link OllamaHealthIndicator} (issue #141) contra um servidor HTTP local de verdade —
 * o que está sob teste é justamente a chamada de rede, o timeout e o parse da resposta.
 */
class OllamaHealthIndicatorTest {

  private HttpServer servidor;
  private final AtomicInteger chamadas = new AtomicInteger();

  @AfterEach
  void tearDown() {
    if (servidor != null) {
      servidor.stop(0);
    }
  }

  @Test
  @DisplayName("UP quando o Ollama responde e os dois modelos estao baixados")
  void upComModelosBaixados() throws IOException {
    String url = subirOllama(0, "{\"models\":[{\"name\":\"qwen3:4b\",\"size\":1},"
        + "{\"name\":\"bge-m3:latest\",\"size\":2}]}");

    Health health = indicador(url, "ollama").health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
  }

  @Test
  @DisplayName("DEGRADED (nao DOWN) com modelo ausente, listando qual falta")
  void degradedComModeloAusente() throws IOException {
    String url = subirOllama(0, "{\"models\":[{\"name\":\"bge-m3:latest\"}]}");

    Health health = indicador(url, "ollama").health();

    assertThat(health.getStatus()).isEqualTo(OllamaHealthIndicator.DEGRADED);
    assertThat(health.getDetails().get("modelosAusentes")).isEqualTo(List.of("qwen3:4b"));
  }

  @Test
  @DisplayName("Com provedor de chat por API, so o modelo de embedding e exigido")
  void provedorPorApiExigeSoEmbedding() throws IOException {
    String url = subirOllama(0, "{\"models\":[{\"name\":\"bge-m3:latest\"}]}");

    Health health = indicador(url, "openai").health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
  }

  @Test
  @DisplayName("DEGRADED com o Ollama fora do ar, sem vazar host nem excecao nos detalhes")
  void degradedComOllamaForaDoAr() throws IOException {
    int portaLivre;
    try (ServerSocket socket = new ServerSocket(0)) {
      portaLivre = socket.getLocalPort();
    }

    Health health = indicador("http://127.0.0.1:" + portaLivre, "ollama").health();

    assertThat(health.getStatus()).isEqualTo(OllamaHealthIndicator.DEGRADED);
    assertThat(health.getDetails()).containsOnlyKeys("motivo");
  }

  @Test
  @DisplayName("Ollama pendurado: o health respeita o proprio timeout curto")
  void ollamaPenduradoRespeitaTimeout() throws IOException {
    String url = subirOllama(3_000, "{\"models\":[]}");

    long inicio = System.nanoTime();
    Health health = indicador(url, "ollama").health();
    long decorridoMs = Duration.ofNanos(System.nanoTime() - inicio).toMillis();

    assertThat(health.getStatus()).isEqualTo(OllamaHealthIndicator.DEGRADED);
    assertThat(decorridoMs).isLessThan(2_500);
  }

  @Test
  @DisplayName("Resultado reaproveitado dentro da janela de cache: uma chamada ao Ollama so")
  void resultadoEmCache() throws IOException {
    String url = subirOllama(0, "{\"models\":[{\"name\":\"qwen3:4b\"},{\"name\":\"bge-m3\"}]}");
    OllamaHealthIndicator indicador = indicador(url, "ollama");

    indicador.health();
    indicador.health();
    indicador.health();

    assertThat(chamadas.get()).isEqualTo(1);
  }

  @Test
  @DisplayName("Nome sem tag equivale a :latest (como o Ollama lista)")
  void normalizaTagLatest() {
    assertThat(OllamaHealthIndicator.normalizar("bge-m3")).isEqualTo("bge-m3:latest");
    assertThat(OllamaHealthIndicator.normalizar("qwen3:4b")).isEqualTo("qwen3:4b");
  }

  private OllamaHealthIndicator indicador(String url, String provedorChat) {
    AssistenteProperties propriedades = new AssistenteProperties();
    propriedades.getSaude().setTimeout(Duration.ofMillis(500));
    propriedades.getSaude().setCache(Duration.ofMinutes(1));
    return new OllamaHealthIndicator(url, provedorChat, "qwen3:4b", "bge-m3", propriedades);
  }

  private String subirOllama(long atrasoMs, String corpo) throws IOException {
    servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    servidor.createContext("/api/tags", troca -> {
      chamadas.incrementAndGet();
      try {
        Thread.sleep(atrasoMs);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      byte[] bytes = corpo.getBytes(StandardCharsets.UTF_8);
      troca.getResponseHeaders().add("Content-Type", "application/json");
      try {
        troca.sendResponseHeaders(200, bytes.length);
        try (OutputStream saida = troca.getResponseBody()) {
          saida.write(bytes);
        }
      } catch (IOException clienteDesistiu) {
        // o cliente estourou o timeout e fechou a conexao: esperado no teste de timeout
      }
    });
    servidor.start();
    return "http://127.0.0.1:" + servidor.getAddress().getPort();
  }
}
