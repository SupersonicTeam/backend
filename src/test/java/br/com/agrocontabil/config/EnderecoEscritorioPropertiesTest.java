package br.com.agrocontabil.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Propriedades do endereco do escritorio: dominio base e chave do ask (#464)")
class EnderecoEscritorioPropertiesTest {

  private static EnderecoEscritorioProperties com(String base, String chave) {
    return new EnderecoEscritorioProperties(base, chave,
        new EnderecoEscritorioProperties.Endereco(Duration.ofDays(30), Duration.ofDays(90)),
        new EnderecoEscritorioProperties.Publico(30, 10000));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "  ", "0123456789abcdef0123456789abcdef",
      "Chave_Segura-na-URL_0123456789abcdefXYZ"})
  @DisplayName("chave vazia (ask desligado) ou de 32+ caracteres seguros na URL sobe")
  void chaveAceita(String chave) {
    assertThat(com("agroconia.com.br", chave).tlsAskChave()).isEqualTo(chave);
  }

  @ParameterizedTest
  @ValueSource(strings = {"curta-demais", "AbCdEfGhIjKlMnOpQrStUvWxYz0123456+/=",
      "chave com espaco no meio 0123456789abc", "chave%2Bcodificada0123456789abcdefgh"})
  @DisplayName("chave curta ou com caractere que a URL altera (+, /, =, espaco, %) nao sobe")
  void chaveRecusada(String chave) {
    assertThatThrownBy(() -> com("agroconia.com.br", chave))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("tls-ask-chave");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "Agroconia.com.br", ".agroconia.com.br", "agroconia.com.br."})
  @DisplayName("dominio base em branco, com maiuscula ou ponto na ponta nao sobe")
  void dominioRecusado(String base) {
    assertThatThrownBy(() -> com(base, "")).isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("toString nunca mostra a chave, e o host junta slug e dominio base")
  void semChaveNoToString() {
    EnderecoEscritorioProperties props = com("agroconia.com.br",
        "0123456789abcdef0123456789abcdef");
    assertThat(props.toString()).doesNotContain("0123456789abcdef").contains("(definida)");
    assertThat(com("agroconia.com.br", "").toString()).contains("(vazia)");
    assertThat(props.host("ruraloeste")).isEqualTo("ruraloeste.agroconia.com.br");
  }
}
