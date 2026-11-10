package br.com.agrocontabil.common;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.validation.SlugEscritorioValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Forma do slug do escritorio (#464)")
class FormatoSlugTest {

  private final SlugEscritorioValidator validador = new SlugEscritorioValidator();

  @ParameterizedTest
  @ValueSource(strings = {"ruraloeste", "rural-oeste", "abc", "a1b", "escritorio2026",
      "contabilidade-rural-oeste-do-pr1"})
  @DisplayName("forma valida: 3 a 32 caracteres, minusculas, digitos e hifen no meio")
  void valido(String slug) {
    assertThat(FormatoSlug.valido(slug)).isTrue();
    assertThat(validador.isValid(slug, null)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "ab", "-rural", "rural-", "rural--oeste", "xn--rural",
      "rural_oeste", "rural.oeste", "rural oeste", "ruraloeste!", "123", "2026",
      "contabilidade-rural-oeste-do-pr12", "rúraloeste", "ruraloeste\u0131",
      "rural\u2010oeste"})
  @DisplayName("forma invalida: curto, longo, hifen na ponta, --, acento, ponto, so digitos")
  void invalido(String slug) {
    assertThat(FormatoSlug.valido(FormatoSlug.normalizar(slug))).isFalse();
    assertThat(validador.isValid(slug, null)).isFalse();
  }

  @Test
  @DisplayName("normaliza espacos nas pontas e maiusculas antes de validar; nulo passa ao @NotNull")
  void normaliza() {
    assertThat(FormatoSlug.normalizar("  RuralOeste ")).isEqualTo("ruraloeste");
    assertThat(validador.isValid("  RuralOeste ", null)).isTrue();
    assertThat(FormatoSlug.normalizar(null)).isNull();
    assertThat(FormatoSlug.valido(null)).isFalse();
    assertThat(validador.isValid(null, null)).isTrue();
    // Maiuscula com regra propria de caixa (I com ponto turco) nao vira ASCII.
    assertThat(validador.isValid("RURAL\u0130", null)).isFalse();
  }
}
