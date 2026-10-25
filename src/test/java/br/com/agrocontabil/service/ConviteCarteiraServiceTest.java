package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ConviteCarteiraService — texto do e-mail (#177)")
class ConviteCarteiraServiceTest {

  @Test
  @DisplayName("nome do escritorio sai sem quebra de linha e no tamanho do e-mail")
  void nomeParaEmail() {
    assertThat(ConviteCarteiraService.nomeParaEmail("Escritorio\r\nBcc: alguem@x.com"))
        .doesNotContain("\n").doesNotContain("\r");
    assertThat(ConviteCarteiraService.nomeParaEmail("x".repeat(500))).hasSize(120);
    assertThat(ConviteCarteiraService.nomeParaEmail(null)).isEmpty();
  }
}
