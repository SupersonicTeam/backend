package br.com.agrocontabil.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.agrocontabil.config.AntivirusProperties.Modo;
import br.com.agrocontabil.service.ClamdCliente;
import br.com.agrocontabil.service.VerificacaoAntivirus;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("AntivirusConfig — guardas do antivirus dos anexos (#227)")
class AntivirusConfigTest {

  private static AntivirusProperties props(Modo modo) {
    return new AntivirusProperties(modo, "localhost", 3310, Duration.ofSeconds(2),
        Duration.ofSeconds(30), 20);
  }

  private static VerificacaoAntivirus criar(Modo modo, String... perfis) {
    MockEnvironment ambiente = new MockEnvironment();
    ambiente.setActiveProfiles(perfis);
    return new AntivirusConfig().verificacaoAntivirus(props(modo), ambiente);
  }

  @Test
  @DisplayName("CLAMAV em qualquer profile, sem conectar na subida")
  void clamavSempre() {
    assertThat(criar(Modo.CLAMAV, "prod")).isInstanceOf(ClamdCliente.class);
    assertThat(criar(Modo.CLAMAV)).isInstanceOf(ClamdCliente.class);
  }

  @Test
  @DisplayName("SEM_VERIFICACAO so em dev/test: homolog, prod, outro nome ou nenhum impedem a"
      + " subida")
  void semVerificacaoSoEmDev() {
    assertThat(criar(Modo.SEM_VERIFICACAO, "dev").verificar(new byte[] {1}).infectado())
        .isFalse();
    for (String[] perfis : new String[][] {{"homolog"}, {"prod"}, {"producao"}, {}}) {
      assertThatThrownBy(() -> criar(Modo.SEM_VERIFICACAO, perfis))
          .isInstanceOf(IllegalStateException.class).hasMessageContaining("SEM_VERIFICACAO");
    }
  }

  @Test
  @DisplayName("porta, timeouts e lote fora da faixa impedem a subida")
  void propriedadesInvalidas() {
    assertThatThrownBy(() -> new AntivirusProperties(Modo.CLAMAV, "h", 0, Duration.ofSeconds(1),
        Duration.ofSeconds(1), 1)).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new AntivirusProperties(Modo.CLAMAV, "h", 3310, Duration.ZERO,
        Duration.ofSeconds(1), 1)).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new AntivirusProperties(Modo.CLAMAV, "h", 3310,
        Duration.ofSeconds(1), Duration.ofSeconds(1), 0))
        .isInstanceOf(IllegalStateException.class);
  }
}
