package br.com.agrocontabil.config;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.service.ClamdCliente;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

/**
 * Health do ClamAV (issue #227) contra um clamd falso: {@code PING}/{@code PONG}, DEGRADED (nunca
 * DOWN) com ele fora do ar, e o cache que impede cada visitante do health de abrir uma conexão.
 */
@DisplayName("ClamAvHealthIndicator (#227)")
class ClamAvHealthIndicatorTest {

  private ServerSocket servidor;
  private final AtomicInteger conexoes = new AtomicInteger();

  @AfterEach
  void fechar() throws IOException {
    if (servidor != null) {
      servidor.close();
    }
  }

  /** clamd falso que responde {@code resposta} a cada {@code zPING}. */
  private int clamdFalso(String resposta) throws IOException {
    servidor = new ServerSocket(0);
    CompletableFuture.runAsync(() -> {
      while (!servidor.isClosed()) {
        try (Socket conexao = servidor.accept()) {
          conexoes.incrementAndGet();
          byte[] comando = conexao.getInputStream().readNBytes("zPING\0".length());
          if ("zPING\0".equals(new String(comando, StandardCharsets.US_ASCII))) {
            conexao.getOutputStream().write((resposta + "\0").getBytes(StandardCharsets.US_ASCII));
          }
        } catch (IOException e) {
          return;
        }
      }
    });
    return servidor.getLocalPort();
  }

  private static ClamAvHealthIndicator indicador(int porta, Duration cache) {
    return new ClamAvHealthIndicator(new ClamdCliente("127.0.0.1", porta, Duration.ofSeconds(1),
        Duration.ofSeconds(1)), cache);
  }

  @Test
  @DisplayName("UP quando o clamd responde PONG")
  void up() throws IOException {
    Health health = indicador(clamdFalso("PONG"), Duration.ZERO).health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
  }

  @Test
  @DisplayName("DEGRADED, nunca DOWN, com o clamd fora do ar ou respondendo outra coisa")
  void degraded() throws IOException {
    int livre;
    try (ServerSocket socket = new ServerSocket(0)) {
      livre = socket.getLocalPort();
    }
    assertThat(indicador(livre, Duration.ZERO).health().getStatus())
        .isEqualTo(ClamAvHealthIndicator.DEGRADED);
    assertThat(indicador(clamdFalso("UNKNOWN COMMAND"), Duration.ZERO).health().getStatus())
        .isEqualTo(ClamAvHealthIndicator.DEGRADED);
    assertThat(ClamAvHealthIndicator.DEGRADED.getCode()).isEqualTo("DEGRADED");
  }

  @Test
  @DisplayName("resultado reaproveitado dentro da janela de cache")
  void cache() throws IOException {
    ClamAvHealthIndicator indicador = indicador(clamdFalso("PONG"), Duration.ofMinutes(1));

    indicador.health();
    indicador.health();
    indicador.health();

    assertThat(conexoes.get()).isEqualTo(1);
  }
}
