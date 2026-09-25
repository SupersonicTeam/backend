package br.com.agrocontabil.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Validação da configuração SEFAZ na subida (issue #83).
 *
 * <p>O invariante que importa: o pior caso de uma transmissão (envio + consulta de reconciliação,
 * cada uma com retry e timeouts) tem de caber em {@code transmissao.timeout-ms}. Se não couber, o
 * job de reconciliação da issue #96 tiraria de {@code ENVIANDO} uma nota cuja transmissão ainda
 * está viva — e a tx2 dessa transmissão aplicaria o desfecho por cima.
 */
class SefazPropertiesTest {

  private final ApplicationContextRunner contexto = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
      .withUserConfiguration(SefazConfig.class);

  @Test
  @DisplayName("padroes sao validos: pior caso de 244 s cabe nos 600 s da transmissao")
  void padroesSaoValidos() {
    SefazProperties props = new SefazProperties();

    assertThatCode(props::afterPropertiesSet).doesNotThrowAnyException();
    // 3 x (10 s + 30 s) + 2 x 1 s = 122 s por operacao; envio + reconciliacao = 244 s.
    assertThat(props.piorCasoOperacaoMs()).isEqualTo(122_000L);
    assertThat(props.piorCasoTransmissaoMs()).isEqualTo(244_000L);
  }

  @Test
  @DisplayName("nao sobe se a transmissao puder durar mais que o timeout de reconciliacao")
  void contextoFalhaQuandoInvarianteViolado() {
    contexto
        .withPropertyValues(
            "app.nfe.sefaz.timeout.read-ms=120000",
            "app.nfe.sefaz.transmissao.timeout-ms=600000")
        .run(ctx -> {
          // 2 x [3 x (10 s + 120 s) + 2 s] = 784 s > 600 s.
          assertThat(ctx).hasFailed();
          assertThat(ctx.getStartupFailure())
              .rootCause()
              .isInstanceOf(IllegalStateException.class)
              .hasMessageContaining("transmissao.timeout-ms");
        });
  }

  @Test
  @DisplayName("o binding de verdade chega aos valores configurados e sobe com eles")
  void contextoSobeComValoresValidos() {
    contexto
        .withPropertyValues(
            "app.nfe.sefaz.timeout.connect-ms=5000",
            "app.nfe.sefaz.timeout.read-ms=20000",
            "app.nfe.sefaz.sync.lote-tamanho=50",
            "app.nfe.sefaz.sync.max-falhas-consecutivas=3")
        .run(ctx -> {
          assertThat(ctx).hasNotFailed();
          SefazProperties props = ctx.getBean(SefazProperties.class);
          assertThat(props.getTimeout().getConnectMs()).isEqualTo(5000L);
          assertThat(props.getTimeout().getReadMs()).isEqualTo(20000L);
          assertThat(props.getSync().getLoteTamanho()).isEqualTo(50);
          assertThat(props.getSync().getMaxFalhasConsecutivas()).isEqualTo(3);
        });
  }

  @Test
  @DisplayName("recusa valores fora de faixa")
  void recusaValoresForaDeFaixa() {
    SefazProperties loteZero = new SefazProperties();
    loteZero.getSync().setLoteTamanho(0);
    assertThatThrownBy(loteZero::afterPropertiesSet)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("lote-tamanho");

    SefazProperties semTentativa = new SefazProperties();
    semTentativa.getRetry().setMaxAttempts(0);
    assertThatThrownBy(semTentativa::afterPropertiesSet)
        .hasMessageContaining("max-attempts");

    SefazProperties semDisjuntor = new SefazProperties();
    semDisjuntor.getSync().setMaxFalhasConsecutivas(0);
    assertThatThrownBy(semDisjuntor::afterPropertiesSet)
        .hasMessageContaining("max-falhas-consecutivas");

    SefazProperties semTimeout = new SefazProperties();
    semTimeout.getTimeout().setReadMs(0);
    assertThatThrownBy(semTimeout::afterPropertiesSet)
        .hasMessageContaining("read-ms");
  }
}
