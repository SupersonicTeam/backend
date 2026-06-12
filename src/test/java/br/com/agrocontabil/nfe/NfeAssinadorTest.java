package br.com.agrocontabil.nfe;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.config.CertificadoDigitalProperties;
import org.junit.jupiter.api.Test;

class NfeAssinadorTest {

  private NfeAssinador novoAssinador() {
    var props = new CertificadoDigitalProperties("classpath:certs/nfe-teste.p12",
        "changeit", "nfe-teste");
    return new NfeAssinador(new KeyStoreCertificadoProvider(props));
  }

  /** XML mínimo com a estrutura NFe>infNFe@Id exigida pela assinatura. */
  private String xmlNfe(String id) {
    return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        + "<NFe xmlns=\"http://www.portalfiscal.inf.br/nfe\">"
        + "<infNFe Id=\"" + id + "\" versao=\"4.00\"><ide><cUF>41</cUF></ide></infNFe>"
        + "</NFe>";
  }

  @Test
  void shouldProduceEnvelopedSignatureReferencingInfNFeId() {
    String id = "NFe41250600000000000000000000000000000000000001";
    String assinado = novoAssinador().assinar(xmlNfe(id), id);

    assertThat(assinado).contains("Signature");
    assertThat(assinado).contains("#" + id);                 // Reference URI
    assertThat(assinado).contains("rsa-sha1");               // SignatureMethod
    assertThat(assinado).contains("xmldsig#sha1");           // DigestMethod
    assertThat(assinado).contains("REC-xml-c14n-20010315");  // C14N inclusiva
    assertThat(assinado).contains("X509Certificate");        // KeyInfo
  }

  @Test
  void shouldInsertSignatureAsSiblingOfInfNFe() {
    String id = "NFe41250600000000000000000000000000000000000002";
    String assinado = novoAssinador().assinar(xmlNfe(id), id);
    int fimInf = assinado.indexOf("</infNFe>");
    int iniSig = assinado.indexOf("Signature");
    assertThat(fimInf).isGreaterThan(0);
    assertThat(iniSig).isGreaterThan(fimInf);
  }
}
