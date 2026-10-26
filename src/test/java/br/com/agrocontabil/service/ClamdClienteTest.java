package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.agrocontabil.service.VerificacaoAntivirus.ResultadoVerificacao;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Protocolo {@code INSTREAM} do clamd (issue #227) contra um servidor TCP falso: o enquadramento
 * do arquivo em blocos, a leitura das três respostas e a falha fechada (queda e resposta sem
 * veredito nunca viram "limpo").
 */
@DisplayName("ClamdCliente: INSTREAM do clamd (#227)")
class ClamdClienteTest {

  private ServerSocket servidor;

  @AfterEach
  void fechar() throws IOException {
    if (servidor != null) {
      servidor.close();
    }
  }

  /** Sobe um clamd falso que lê um INSTREAM, devolve o arquivo remontado e responde. */
  private CompletableFuture<byte[]> clamdFalso(String resposta) throws IOException {
    servidor = new ServerSocket(0);
    return CompletableFuture.supplyAsync(() -> {
      try (Socket conexao = servidor.accept()) {
        DataInputStream entrada = new DataInputStream(conexao.getInputStream());
        byte[] comando = new byte["zINSTREAM\0".length()];
        entrada.readFully(comando);
        assertThat(new String(comando, StandardCharsets.US_ASCII)).isEqualTo("zINSTREAM\0");
        ByteArrayOutputStream arquivo = new ByteArrayOutputStream();
        int tamanho;
        while ((tamanho = entrada.readInt()) > 0) {
          assertThat(tamanho).isLessThanOrEqualTo(ClamdCliente.BLOCO);
          byte[] bloco = new byte[tamanho];
          entrada.readFully(bloco);
          arquivo.write(bloco);
        }
        conexao.getOutputStream().write((resposta + "\0").getBytes(StandardCharsets.US_ASCII));
        return arquivo.toByteArray();
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
    });
  }

  private ClamdCliente cliente(int porta, Duration leitura) {
    return new ClamdCliente("127.0.0.1", porta, Duration.ofSeconds(2), leitura);
  }

  @Test
  @DisplayName("arquivo maior que um bloco chega inteiro; 'stream: OK' = limpo")
  void limpoComVariosBlocos() throws Exception {
    byte[] arquivo = new byte[ClamdCliente.BLOCO * 2 + 17];
    Arrays.fill(arquivo, (byte) 'a');
    CompletableFuture<byte[]> recebido = clamdFalso("stream: OK");

    ResultadoVerificacao resultado = cliente(servidor.getLocalPort(), Duration.ofSeconds(5))
        .verificar(arquivo);

    assertThat(resultado).isEqualTo(ResultadoVerificacao.limpo());
    assertThat(recebido.get(5, TimeUnit.SECONDS)).isEqualTo(arquivo);
  }

  @Test
  @DisplayName("'stream: <assinatura> FOUND' = infectado, com o nome da assinatura")
  void infectado() throws Exception {
    clamdFalso("stream: Win.Test.EICAR_HDB-1 FOUND");

    ResultadoVerificacao resultado = cliente(servidor.getLocalPort(), Duration.ofSeconds(5))
        .verificar("X5O!P%@AP".getBytes(StandardCharsets.US_ASCII));

    assertThat(resultado.infectado()).isTrue();
    assertThat(resultado.assinatura()).isEqualTo("Win.Test.EICAR_HDB-1");
  }

  @Test
  @DisplayName("resposta ERROR nao e veredito: falha fechada, sem derrubar o lote")
  void erroDoClamd() throws Exception {
    clamdFalso("INSTREAM size limit exceeded. ERROR");

    assertThatThrownBy(() -> cliente(servidor.getLocalPort(), Duration.ofSeconds(5))
        .verificar(new byte[] {1, 2, 3}))
        .isInstanceOfSatisfying(AntivirusIndisponivelException.class,
            e -> assertThat(e.isForaDoAr()).isFalse());
  }

  @Test
  @DisplayName("conexao recusada = antivirus fora do ar")
  void conexaoRecusada() throws Exception {
    int porta;
    try (ServerSocket livre = new ServerSocket(0)) {
      porta = livre.getLocalPort();
    }

    assertThatThrownBy(() -> cliente(porta, Duration.ofSeconds(1)).verificar(new byte[] {1}))
        .isInstanceOfSatisfying(AntivirusIndisponivelException.class,
            e -> assertThat(e.isForaDoAr()).isTrue());
  }

  @Test
  @DisplayName("clamd que nao responde: tempo esgotado = fora do ar, nunca limpo")
  void semResposta() throws Exception {
    servidor = new ServerSocket(0);
    CompletableFuture.runAsync(() -> {
      try (Socket conexao = servidor.accept()) {
        conexao.getInputStream().readAllBytes();
      } catch (IOException e) {
        // o cliente desistiu: esperado
      }
    });

    assertThatThrownBy(() -> cliente(servidor.getLocalPort(), Duration.ofMillis(300))
        .verificar(new byte[] {1}))
        .isInstanceOfSatisfying(AntivirusIndisponivelException.class,
            e -> assertThat(e.isForaDoAr()).isTrue());
  }

  @Test
  @DisplayName("interpretar: so OK e FOUND sao veredito; o resto e recusa")
  void interpretar() {
    assertThat(ClamdCliente.interpretar("stream: OK")).isEqualTo(ResultadoVerificacao.limpo());
    assertThat(ClamdCliente.interpretar("  stream: Eicar-Signature FOUND \n").assinatura())
        .isEqualTo("Eicar-Signature");
    assertThat(ClamdCliente.interpretar("stream: " + "A".repeat(300) + " FOUND").assinatura())
        .hasSize(200);
    for (String semVeredito : new String[] {"", "PONG", "stream: FOUND", "stream: OKAY",
        "stream: Algo ERROR", "OK"}) {
      assertThatThrownBy(() -> ClamdCliente.interpretar(semVeredito))
          .as(semVeredito)
          .isInstanceOfSatisfying(AntivirusIndisponivelException.class,
              e -> assertThat(e.isForaDoAr()).isFalse());
    }
  }
}
