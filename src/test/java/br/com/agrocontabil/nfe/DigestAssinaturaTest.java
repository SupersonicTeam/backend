package br.com.agrocontabil.nfe;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DigestAssinatura — o XML importado e o que a SEFAZ autorizou (#223)")
class DigestAssinaturaTest {

  private static final String XML = "<NFe><ds:Signature><ds:SignedInfo><ds:Reference>"
      + "<ds:DigestValue>\n  abc123+/= \n</ds:DigestValue></ds:Reference></ds:SignedInfo>"
      + "</ds:Signature></NFe>";

  @Test
  @DisplayName("extrai o DigestValue com ou sem prefixo e com espacos em volta")
  void extrai() {
    assertThat(DigestAssinatura.extrair(XML)).contains("abc123+/=");
    assertThat(DigestAssinatura.extrair("<NFe><DigestValue>x=</DigestValue></NFe>")).contains("x=");
    assertThat(DigestAssinatura.extrair("<NFe/>")).isEmpty();
    assertThat(DigestAssinatura.extrair(null)).isEmpty();
  }

  @Test
  @DisplayName("confere: digVal igual passa, diferente ou XML sem assinatura nao; digVal nulo nao "
      + "tem o que conferir")
  void confere() {
    assertThat(DigestAssinatura.confere(XML, "abc123+/=")).isTrue();
    assertThat(DigestAssinatura.confere(XML, " abc123+/= ")).isTrue();
    assertThat(DigestAssinatura.confere(XML, "outro=")).isFalse();
    assertThat(DigestAssinatura.confere("<NFe/>", "abc123+/=")).isFalse();
    assertThat(DigestAssinatura.confere(XML, null)).isTrue();
  }
}
