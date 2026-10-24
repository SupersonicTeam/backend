package br.com.agrocontabil.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Regra única de normalização da busca das notas (issue #174). */
class NormalizacaoBuscaTest {

  @Test
  @DisplayName("Tira acento, passa a minúsculas e colapsa espaços")
  void normaliza() {
    assertThat(NormalizacaoBusca.normalizar("  Cooperativa   AGRÍCOLA\tdo Paraná "))
        .isEqualTo("cooperativa agricola do parana");
    assertThat(NormalizacaoBusca.normalizar("Ação Çedilha Ñandu")).isEqualTo("acao cedilha nandu");
    assertThat(NormalizacaoBusca.normalizar(null)).isEmpty();
  }

  @Test
  @DisplayName("Junta as partes ignorando as nulas")
  void juntaPartes() {
    assertThat(NormalizacaoBusca.juntar(123L, "José", null, "Venda de Soja"))
        .isEqualTo("123 jose venda de soja");
  }

  @Test
  @DisplayName("Curingas do LIKE e a barra viram texto")
  void escapaCuringas() {
    assertThat(NormalizacaoBusca.escaparLike("50%_a\\b")).isEqualTo("50\\%\\_a\\\\b");
  }
}
