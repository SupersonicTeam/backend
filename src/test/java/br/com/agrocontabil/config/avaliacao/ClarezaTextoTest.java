package br.com.agrocontabil.config.avaliacao;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Medidas automáticas de clareza (issue #264). */
class ClarezaTextoTest {

  @Test
  @DisplayName("Frases e palavras: abreviação e valor em reais não quebram a frase")
  void frasesEPalavras() {
    ClarezaTexto.Medida m = ClarezaTexto.medir(
        "Voce comprou R$ 12.500,00 de adubo. Pelo art. 138 da lei, ha reducao!\nConfira.");

    assertThat(m.frases()).isEqualTo(3);
    assertThat(m.palavras()).isEqualTo(14);
    assertThat(m.palavrasPorFrase()).isEqualTo(14 / 3.0);
  }

  @Test
  @DisplayName("Item numerado não vira frase; CBS pelo nome da lei conta como explicada")
  void listaENomeLegal() {
    ClarezaTexto.Medida m = ClarezaTexto.medir("1. Emita a nota\n2. Pague a guia");

    assertThat(m.frases()).isEqualTo(2);
    assertThat(m.palavrasPorFrase()).isEqualTo(3.0);
    assertThat(ClarezaTexto.medir("A Contribuição Social sobre Bens e Serviços (CBS) vale.")
        .siglasSemExplicacao()).isEmpty();
  }

  @Test
  @DisplayName("Texto vazio ou nulo mede zero")
  void vazio() {
    assertThat(ClarezaTexto.medir(null).frases()).isZero();
    assertThat(ClarezaTexto.medir("  ").palavrasPorFrase()).isZero();
  }

  @Test
  @DisplayName("Sigla sem explicação conta; com a explicação em qualquer lugar, não")
  void siglas() {
    assertThat(ClarezaTexto.medir("O IBS e a CBS entram na NF-e.").siglasSemExplicacao())
        .containsExactly("IBS", "CBS", "NF-e");
    assertThat(ClarezaTexto.medir(
        "O IBS (Imposto sobre Bens e Serviços) vai na nota fiscal eletrônica, a NFe.")
        .siglasSemExplicacao()).isEmpty();
    // "CBS" dentro de outra palavra não é a sigla; funrural vale sem caixa.
    assertThat(ClarezaTexto.medir("ABCBS e o funrural.").siglasSemExplicacao())
        .containsExactly("Funrural");
  }
}
