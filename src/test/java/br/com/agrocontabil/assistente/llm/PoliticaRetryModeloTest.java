package br.com.agrocontabil.assistente.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.agrocontabil.config.AssistenteConfig;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryProperties;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.ResourceAccessException;

/**
 * Testes do retry das chamadas ao modelo (issue #141): só se repete o que não chegou ao destino ou
 * o erro transitório do provedor — nunca o read-timeout.
 */
class PoliticaRetryModeloTest {

  private final RetryTemplate retry = templateCom(3);

  @Test
  @DisplayName("Read-timeout NAO e re-tentado: a chamada lenta nao prende a thread 2x")
  void readTimeoutNaoRetenta() {
    assertThat(tentativasAte(new ResourceAccessException("I/O error",
        new SocketTimeoutException("Read timed out")))).isEqualTo(1);
  }

  @Test
  @DisplayName("Conexao recusada (Ollama fora) e re-tentada ate o limite")
  void conexaoRecusadaRetenta() {
    assertThat(tentativasAte(new ResourceAccessException("I/O error",
        new ConnectException("Connection refused")))).isEqualTo(3);
  }

  @Test
  @DisplayName("Host desconhecido e connect-timeout sao re-tentados (a requisicao nao saiu)")
  void hostDesconhecidoEConnectTimeoutRetentam() {
    assertThat(tentativasAte(new ResourceAccessException("I/O error",
        new UnknownHostException("ollama")))).isEqualTo(3);
    assertThat(tentativasAte(new ResourceAccessException("I/O error",
        new SocketTimeoutException("Connect timed out")))).isEqualTo(3);
  }

  @Test
  @DisplayName("Erro transitorio do provedor (429/5xx) e re-tentado; permanente (4xx) nao")
  void transitorioRetentaPermanenteNao() {
    assertThat(tentativasAte(new TransientAiException("HTTP 503"))).isEqualTo(3);
    assertThat(tentativasAte(new NonTransientAiException("HTTP 401"))).isEqualTo(1);
  }

  @Test
  @DisplayName("429 (limite de taxa) e re-tentado ate o limite; 400 e 401 nao")
  void limiteDeTaxaRetentaDemaisErrosDoClienteNao() {
    assertThat(tentativasAte(new LimiteTaxaProvedorException("HTTP 429 - rate limit")))
        .isEqualTo(3);
    assertThat(tentativasAte(new NonTransientAiException("HTTP 400 - bad request"))).isEqualTo(1);
    assertThat(tentativasAte(new NonTransientAiException("HTTP 401 - invalid api key")))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("429 que passa na nova tentativa devolve a resposta, sem falha")
  void limiteDeTaxaPassaNaSegundaTentativa() {
    AtomicInteger tentativas = new AtomicInteger();

    String resposta = retry.execute(contexto -> {
      if (tentativas.incrementAndGet() == 1) {
        throw new LimiteTaxaProvedorException("HTTP 429 - rate limit");
      }
      return "ok";
    });

    assertThat(resposta).isEqualTo("ok");
    assertThat(tentativas).hasValue(2);
  }

  @Test
  @DisplayName("Falha inesperada (bug local) nao e re-tentada")
  void falhaInesperadaNaoRetenta() {
    assertThat(tentativasAte(new IllegalStateException("bug"))).isEqualTo(1);
  }

  private int tentativasAte(RuntimeException falha) {
    AtomicInteger tentativas = new AtomicInteger();
    assertThatThrownBy(() -> retry.execute(contexto -> {
      tentativas.incrementAndGet();
      throw falha;
    })).isSameAs(falha);
    return tentativas.get();
  }

  static RetryTemplate templateCom(int maxTentativas) {
    SpringAiRetryProperties propriedades = new SpringAiRetryProperties();
    propriedades.setMaxAttempts(maxTentativas);
    propriedades.getBackoff().setInitialInterval(Duration.ofMillis(1));
    propriedades.getBackoff().setMaxInterval(Duration.ofMillis(1));
    return new AssistenteConfig().retryTemplate(propriedades);
  }
}
