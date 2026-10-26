package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.service.VerificacaoAntivirus.ResultadoVerificacao;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * {@link ClamdCliente} contra o ClamAV de verdade (issue #227), na mesma imagem do compose.
 * Ignorado sem Docker, como os testes do PostgreSQL; roda na CI.
 *
 * <p>A imagem já traz as assinaturas; o {@code freshclam} fica desligado
 * ({@code CLAMAV_NO_FRESHCLAMD}) para o teste não depender da rede nem do limite de download do
 * espelho do ClamAV.
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("ClamdCliente contra o ClamAV real (#227)")
class ClamdClienteIntegrationTest {

  /** O arquivo de teste padrão EICAR (não é vírus; todo antivírus o reconhece). */
  private static final byte[] EICAR = ("X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST"
      + "-FILE!$H+H*").getBytes(StandardCharsets.US_ASCII);

  @Container
  @SuppressWarnings("resource") // ciclo de vida gerenciado pela extensao @Testcontainers
  static final GenericContainer<?> CLAMAV =
      new GenericContainer<>(DockerImageName.parse("clamav/clamav:1.4.3"))
          .withEnv("CLAMAV_NO_FRESHCLAMD", "true")
          .withExposedPorts(3310)
          // A base leva de 30 s a 1 min para carregar.
          .waitingFor(Wait.forLogMessage(".*socket found, clamd started.*", 1)
              .withStartupTimeout(Duration.ofMinutes(5)));

  private ClamdCliente cliente() {
    return new ClamdCliente(CLAMAV.getHost(), CLAMAV.getMappedPort(3310), Duration.ofSeconds(5),
        Duration.ofSeconds(60));
  }

  @Test
  @DisplayName("EICAR e infectado, com o nome da assinatura")
  void eicar() {
    ResultadoVerificacao resultado = cliente().verificar(EICAR);

    assertThat(resultado.infectado()).isTrue();
    assertThat(resultado.assinatura()).containsIgnoringCase("eicar");
  }

  @Test
  @DisplayName("PING do health responde PONG")
  void responde() {
    assertThat(cliente().responde()).isTrue();
  }

  @Test
  @DisplayName("PDF comum de varios blocos e limpo")
  void pdfLimpo() {
    byte[] pdf = ("%PDF-1.7\n" + "extrato da conta\n".repeat(10_000))
        .getBytes(StandardCharsets.US_ASCII);

    assertThat(cliente().verificar(pdf)).isEqualTo(ResultadoVerificacao.limpo());
  }
}
