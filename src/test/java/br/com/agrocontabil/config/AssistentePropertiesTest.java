package br.com.agrocontabil.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Validação das propriedades da Ceres na subida (timeouts da issue #157). */
class AssistentePropertiesTest {

  @Test
  @DisplayName("Padroes sobem")
  void padroesValidos() {
    assertThatCode(() -> new AssistenteProperties().afterPropertiesSet())
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("timeout-funcao abaixo de 1s e recusado (resolucao do timeout de transacao)")
  void timeoutFuncaoSubSegundo() {
    AssistenteProperties p = new AssistenteProperties();
    p.setTimeoutFuncao(Duration.ofMillis(500));

    assertThatThrownBy(p::afterPropertiesSet).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("app.assistente.timeout-funcao");
  }

  @Test
  @DisplayName("timeout-funcao nao pode alcancar o timeout-pergunta")
  void timeoutFuncaoMaiorQuePergunta() {
    AssistenteProperties p = new AssistenteProperties();
    p.setTimeoutPergunta(Duration.ofSeconds(10));
    p.setTimeoutFuncao(Duration.ofSeconds(10));

    assertThatThrownBy(p::afterPropertiesSet).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("deve ser menor");
  }

  @Test
  @DisplayName("timeout-pergunta zero ou nulo e recusado")
  void timeoutPerguntaInvalido() {
    AssistenteProperties zero = new AssistenteProperties();
    zero.setTimeoutPergunta(Duration.ZERO);
    AssistenteProperties nulo = new AssistenteProperties();
    nulo.setTimeoutPergunta(null);

    assertThatThrownBy(zero::afterPropertiesSet).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(nulo::afterPropertiesSet).isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("#166: teto de uma pergunta maior que o plano e recusado na subida")
  void tetoMaiorQueOPlano() {
    AssistenteProperties p = new AssistenteProperties();
    // Modelo maior: AI_MAX_TOKENS 8192 -> teto de ~245 creditos, plano de 200.
    p.getCota().setTetoTokensSaidaPorChamada(8192);
    p.getCota().setCreditosPorSemanaProdutor(200);

    assertThatThrownBy(p::afterPropertiesSet).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("teto de uma pergunta");

    // Com a cota desligada (demonstracao), a mesma configuracao sobe.
    p.getCota().setHabilitada(false);
    p.afterPropertiesSet();
  }
}
