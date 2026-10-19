package br.com.agrocontabil.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("LimitadorTaxa — balde de fichas por chave, com teto de memoria (#208)")
class LimitadorTaxaTest {

  @Test
  @DisplayName("ate a capacidade passa; depois, espera positiva ate a proxima ficha")
  void capacidade() {
    LimitadorTaxa limitador = new LimitadorTaxa(3, Duration.ofMinutes(1), 100);

    assertThat(limitador.tentar("1.1.1.1")).isZero();
    assertThat(limitador.tentar("1.1.1.1")).isZero();
    assertThat(limitador.tentar("1.1.1.1")).isZero();
    // 3 fichas por minuto: a proxima volta em ~20 s.
    assertThat(limitador.tentar("1.1.1.1")).isBetween(1L, 21L);
  }

  @Test
  @DisplayName("chaves independentes: um IP no limite nao afeta outro")
  void chavesIndependentes() {
    LimitadorTaxa limitador = new LimitadorTaxa(1, Duration.ofMinutes(1), 100);

    assertThat(limitador.tentar("1.1.1.1")).isZero();
    assertThat(limitador.tentar("1.1.1.1")).isPositive();
    assertThat(limitador.tentar("2.2.2.2")).isZero();
  }

  @Test
  @DisplayName("teto de chaves: a mais antiga e descartada (recomeca cheia), a memoria nao cresce")
  void tetoDeChaves() {
    LimitadorTaxa limitador = new LimitadorTaxa(1, Duration.ofHours(1), 2);

    limitador.tentar("a");
    assertThat(limitador.tentar("a")).isPositive();
    limitador.tentar("b");
    limitador.tentar("c"); // descarta "a"

    assertThat(limitador.tentar("a")).isZero();
  }
}
