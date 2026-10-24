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
  @DisplayName("Escrita que o NFD decompõe sem acento (coreano) volta ao tamanho original")
  void naoExpandeTexto() {
    String hangul = "한국".repeat(100);

    assertThat(NormalizacaoBusca.normalizar(hangul)).isEqualTo(hangul);
    assertThat(NormalizacaoBusca.normalizar("Kråkeslottet Łódź"))
        .isEqualTo("krakeslottet łodz");
  }

  @Test
  @DisplayName("O texto juntado nunca passa do tamanho da coluna")
  void juntarRespeitaOTamanhoDaColuna() {
    assertThat(NormalizacaoBusca.juntar("a".repeat(400), "b".repeat(400)))
        .hasSize(NormalizacaoBusca.TAMANHO_MAXIMO);
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
