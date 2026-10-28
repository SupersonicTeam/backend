package br.com.agrocontabil.nfe;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DigestAssinatura — o XML importado e o que a SEFAZ autorizou (#223)")
class DigestAssinaturaTest {

  private static final String XML = "<NFe><ds:Signature "
      + "xmlns:ds=\"http://www.w3.org/2000/09/xmldsig#\"><ds:SignedInfo><ds:Reference>"
      + "<ds:DigestValue>\n  abc123+/= \n</ds:DigestValue></ds:Reference></ds:SignedInfo>"
      + "</ds:Signature></NFe>";

  @Test
  @DisplayName("extrai o ds:DigestValue com ou sem prefixo e com espacos em volta")
  void extrai() {
    assertThat(DigestAssinatura.extrair(XML)).contains("abc123+/=");
    assertThat(DigestAssinatura.extrair("<NFe><DigestValue xmlns=\"http://www.w3.org/2000/09/"
        + "xmldsig#\">x=</DigestValue></NFe>")).contains("x=");
    // Sem o namespace da assinatura, um DigestValue qualquer nao conta.
    assertThat(DigestAssinatura.extrair("<NFe><DigestValue>x=</DigestValue></NFe>")).isEmpty();
    assertThat(DigestAssinatura.extrair("<NFe/>")).isEmpty();
    assertThat(DigestAssinatura.extrair(null)).isEmpty();
  }

  @Test
  @DisplayName("DigestValue falso em CDATA/texto antes da assinatura nao conta: so o ds:DigestValue "
      + "da assinatura (#223)")
  void ignoraDigestForaDaAssinatura() {
    String forjado = "<NFe xmlns=\"http://www.portalfiscal.inf.br/nfe\"><infNFe><infAdic><infCpl>"
        + "<![CDATA[<DigestValue>digestDaNotaVerdadeira=</DigestValue>]]></infCpl></infAdic>"
        + "</infNFe><Signature xmlns=\"http://www.w3.org/2000/09/xmldsig#\"><SignedInfo>"
        + "<Reference><DigestValue>digestDoForjado=</DigestValue></Reference></SignedInfo>"
        + "</Signature></NFe>";

    assertThat(DigestAssinatura.extrair(forjado)).contains("digestDoForjado=");
    assertThat(DigestAssinatura.confere(forjado, "digestDaNotaVerdadeira=")).isFalse();
  }

  @Test
  @DisplayName("mais de um ds:DigestValue (assinatura embrulhada) nao tem digest confiavel")
  void maisDeUmDigest() {
    String dois = "<NFe><ds:Signature xmlns:ds=\"http://www.w3.org/2000/09/xmldsig#\">"
        + "<ds:DigestValue>a=</ds:DigestValue><ds:DigestValue>b=</ds:DigestValue>"
        + "</ds:Signature></NFe>";

    assertThat(DigestAssinatura.extrair(dois)).isEmpty();
    assertThat(DigestAssinatura.confere(dois, "a=")).isFalse();
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
