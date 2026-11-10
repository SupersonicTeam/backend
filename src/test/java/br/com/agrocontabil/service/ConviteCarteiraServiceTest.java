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

  @Test
  @DisplayName("nome do escritorio sai sem URL, www, e-mail nem dominio (#460)")
  void nomeParaEmailSemEndereco() {
    assertThat(ConviteCarteiraService.nomeParaEmail(
        "Contabilidade Oeste - acesse https://golpe.com/login?x=1 agora"))
        .isEqualTo("Contabilidade Oeste - acesse agora");
    assertThat(ConviteCarteiraService.nomeParaEmail("Escritório www.golpe.com.br"))
        .isEqualTo("Escritório");
    assertThat(ConviteCarteiraService.nomeParaEmail("Fale com suporte@golpe.com"))
        .isEqualTo("Fale com");
    assertThat(ConviteCarteiraService.nomeParaEmail("Pague em golpe.com.br/boleto hoje"))
        .isEqualTo("Pague em hoje");
    assertThat(ConviteCarteiraService.nomeParaEmail("Veja golpe．com"))
        .as("ponto de largura total vira ponto comum (NFKC)").isEqualTo("Veja");
    assertThat(ConviteCarteiraService.nomeParaEmail("HTTP://GOLPE.COM.BR"))
        .isEmpty();
  }

  @Test
  @DisplayName("nome legitimo com ponto, acento e numero continua inteiro (#460)")
  void nomeParaEmailLegitimo() {
    for (String nome : new String[] {"Contabilidade São João Ltda.", "Silva & Filhos S.A.",
        "J.P. Assessoria Rural", "Escritório 3.0 Contábil", "Agro-Contábil Oeste ME"}) {
      assertThat(ConviteCarteiraService.nomeParaEmail(nome)).isEqualTo(nome);
    }
  }
}
