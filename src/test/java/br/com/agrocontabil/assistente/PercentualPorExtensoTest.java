package br.com.agrocontabil.assistente;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Percentual por extenso em número (issue #269). */
class PercentualPorExtensoTest {

  private static List<BigDecimal> valores(String texto) {
    return PercentualPorExtenso.valores(texto);
  }

  private static BigDecimal n(String v) {
    return new BigDecimal(v).stripTrailingZeros();
  }

  @Test
  @DisplayName("Lei 9.528, art. 6º: 'zero vírgula dois por cento' é 0,2")
  void zeroVirgulaDois() {
    assertThat(valores("é de zero vírgula dois por cento, incidente sobre a receita bruta"))
        .containsExactly(n("0.2"));
  }

  @Test
  @DisplayName("Inteiros, compostos e parte decimal com zero à esquerda")
  void inteirosEDecimais() {
    assertThat(valores("sessenta por cento")).containsExactly(n("60"));
    assertThat(valores("vinte e cinco por cento")).containsExactly(n("25"));
    assertThat(valores("cento e vinte por cento")).containsExactly(n("120"));
    assertThat(valores("um vírgula cinco por cento")).containsExactly(n("1.5"));
    assertThat(valores("zero vírgula zero cinco por cento")).containsExactly(n("0.05"));
    assertThat(valores("dois vírgula vinte e cinco por cento")).containsExactly(n("2.25"));
    assertThat(valores("cem por cento")).containsExactly(n("100"));
  }

  @Test
  @DisplayName("Sem acento, em maiúsculas e vários no mesmo texto, na ordem")
  void grafiasEVarios() {
    assertThat(valores("ZERO VIRGULA DOIS POR CENTO e depois trinta por cento"))
        .containsExactly(n("0.2"), n("30"));
  }

  @Test
  @DisplayName("Sem 'por cento', ou palavra que não é número: nada")
  void naoReconhece() {
    assertThat(valores("duas notas fiscais")).isEmpty();
    assertThat(valores("o contribuinte por cento")).isEmpty();
    assertThat(valores("trinta e um dias")).isEmpty();
    assertThat(valores("")).isEmpty();
    assertThat(valores(null)).isEmpty();
  }
}
