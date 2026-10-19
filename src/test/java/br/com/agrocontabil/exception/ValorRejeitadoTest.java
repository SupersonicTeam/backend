package br.com.agrocontabil.exception;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.common.Sensivel;
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

  @Test
  @DisplayName("Campo de senha, token ou segredo nunca tem o valor ecoado (#205)")
  void campoSensivelNaoEcoa() {
    assertThat(GlobalExceptionHandler.campoSensivel("senha")).isTrue();
    assertThat(GlobalExceptionHandler.campoSensivel("novaSenha")).isTrue();
    assertThat(GlobalExceptionHandler.campoSensivel("refreshToken")).isTrue();
    assertThat(GlobalExceptionHandler.campoSensivel("clientSecret")).isTrue();
    assertThat(GlobalExceptionHandler.campoSensivel("email")).isFalse();
    assertThat(GlobalExceptionHandler.campoSensivel(null)).isFalse();
  }

  /** DTO de teste: o campo sensível não tem nome suspeito, só a anotação o protege. */
  record PedidoComPin(@Sensivel String pin, String nome) {
  }

  @Test
  @DisplayName("Campo anotado @Sensivel nunca tem o valor ecoado, mesmo sem nome suspeito")
  void anotacaoSensivel() {
    assertThat(GlobalExceptionHandler.campoSensivel(PedidoComPin.class, "pin")).isTrue();
    assertThat(GlobalExceptionHandler.campoSensivel(PedidoComPin.class, "nome")).isFalse();
    // Caminho aninhado ou classe desconhecida: vale so a rede de seguranca pelo nome.
    assertThat(GlobalExceptionHandler.campoSensivel(null, "senha")).isTrue();
    assertThat(GlobalExceptionHandler.campoSensivel(PedidoComPin.class, "itens[0].pin")).isFalse();
  }
}
