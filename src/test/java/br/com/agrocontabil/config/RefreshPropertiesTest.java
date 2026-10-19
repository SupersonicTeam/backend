package br.com.agrocontabil.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RefreshProperties — configuracao do cookie recusada na subida (#207)")
class RefreshPropertiesTest {

  private static RefreshProperties props(String sameSite, boolean seguro, Duration validade,
      Duration vidaMaxima) {
    return new RefreshProperties(validade, vidaMaxima, "agro_refresh", seguro, sameSite,
        "/api/auth", Duration.ofDays(7));
  }

  @Test
  @DisplayName("padrao (Strict, Secure, 7d de 30d) e valido; Lax sem Secure tambem (dev)")
  void validos() {
    assertThatCode(() -> props("Strict", true, Duration.ofDays(7), Duration.ofDays(30)))
        .doesNotThrowAnyException();
    assertThatCode(() -> props("Lax", false, Duration.ofDays(7), Duration.ofDays(30)))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("SameSite desconhecido ou None sem Secure: a aplicacao nao sobe")
  void sameSiteInvalido() {
    assertThatThrownBy(() -> props("strict-ish", true, Duration.ofDays(7), Duration.ofDays(30)))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> props("None", false, Duration.ofDays(7), Duration.ofDays(30)))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("cookie-seguro");
  }

  @Test
  @DisplayName("validade zero ou maior que a vida maxima: a aplicacao nao sobe")
  void validadeInvalida() {
    assertThatThrownBy(() -> props("Strict", true, Duration.ZERO, Duration.ofDays(30)))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> props("Strict", true, Duration.ofDays(31), Duration.ofDays(30)))
        .isInstanceOf(IllegalStateException.class);
  }
}
