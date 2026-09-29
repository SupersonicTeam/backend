package br.com.agrocontabil.assistente.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.ollama.api.ThinkOption;

/** Testes do {@link ThinkOptionConverter} (issue #141). */
class ThinkOptionConverterTest {

  private final ThinkOptionConverter conversor = new ThinkOptionConverter();

  @Test
  @DisplayName("true/false viram liga/desliga; niveis viram ThinkLevel")
  void converteValoresAceitos() {
    assertThat(conversor.convert("false")).isEqualTo(ThinkOption.ThinkBoolean.DISABLED);
    assertThat(conversor.convert(" TRUE ")).isEqualTo(ThinkOption.ThinkBoolean.ENABLED);
    assertThat(conversor.convert("medium")).isEqualTo(ThinkOption.ThinkLevel.MEDIUM);
  }

  @Test
  @DisplayName("Vazio = nao enviar a opcao (modelo sem suporte a thinking)")
  void vazioNaoEnvia() {
    assertThat(conversor.convert("")).isNull();
  }

  @Test
  @DisplayName("Valor invalido impede a subida em vez de ser ignorado")
  void valorInvalidoFalha() {
    assertThatThrownBy(() -> conversor.convert("talvez"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("think-option");
  }
}
