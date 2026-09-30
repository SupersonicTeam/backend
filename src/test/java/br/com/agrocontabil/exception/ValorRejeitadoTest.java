package br.com.agrocontabil.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Truncamento do rejectedValue ecoado nos erros de validação (issue #144). */
class ValorRejeitadoTest {

  @Test
  @DisplayName("Valor curto volta inteiro; longo volta com 100 caracteres e reticencias")
  void truncaValorLongo() {
    assertThat(GlobalExceptionHandler.resumirValorRejeitado("curto")).isEqualTo("curto");
    assertThat(GlobalExceptionHandler.resumirValorRejeitado("a".repeat(5000)))
        .isEqualTo("a".repeat(100) + "...");
    assertThat(GlobalExceptionHandler.resumirValorRejeitado(null)).isNull();
  }

  @Test
  @DisplayName("Corte por code point: emoji na borda nao vira surrogate solto")
  void naoPartePardeSurrogates() {
    String valor = "a".repeat(99) + "\uD83C\uDF3E" + "b".repeat(50);

    String resumido = GlobalExceptionHandler.resumirValorRejeitado(valor);

    assertThat(resumido).isEqualTo("a".repeat(99) + "\uD83C\uDF3E...");
    assertThat(Character.isHighSurrogate(resumido.charAt(resumido.length() - 4))).isFalse();
  }
}
