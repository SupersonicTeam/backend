package br.com.agrocontabil.nfe;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.agrocontabil.config.DigitalCertificateProperties;
import br.com.agrocontabil.exception.DigitalSignatureException;
import org.junit.jupiter.api.Test;

class SignatureValidatorTest {

  private final KeyStoreCertificateProvider provider = new KeyStoreCertificateProvider(
      new DigitalCertificateProperties("classpath:certs/nfe-teste.p12", "changeit", "nfe-teste"));
  private final NfeSigner assinador = new NfeSigner(provider);
  private final SignatureValidator validator = new SignatureValidator();

  private String xmlNfe(String id) {
    return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        + "<NFe xmlns=\"http://www.portalfiscal.inf.br/nfe\">"
        + "<infNFe Id=\"" + id + "\" versao=\"4.00\"><ide><cUF>41</cUF></ide></infNFe>"
        + "</NFe>";
  }

  @Test
  void validSignatureShouldPass() {
    String id = "NFe41250600000000000000000000000000000000000010";
    String assinado = assinador.sign(xmlNfe(id), id);
    assertThatCode(() -> validator.validate(assinado)).doesNotThrowAnyException();
  }

  @Test
  void tamperedXmlShouldFail() {
    String id = "NFe41250600000000000000000000000000000000000011";
    String assinado = assinador.sign(xmlNfe(id), id);
    // adultera o conteudo assinado (troca cUF) apos a assinatura
    String adulterado = assinado.replace("<cUF>41</cUF>", "<cUF>35</cUF>");
    assertThatThrownBy(() -> validator.validate(adulterado))
        .isInstanceOf(DigitalSignatureException.class);
  }

  @Test
  void xmlWithoutSignatureShouldFail() {
    String id = "NFe41250600000000000000000000000000000000000012";
    assertThatThrownBy(() -> validator.validate(xmlNfe(id)))
        .isInstanceOf(DigitalSignatureException.class);
  }

  @Test
  void signatureWithoutKeyInfoShouldFail() {
    String id = "NFe41250600000000000000000000000000000000000013";
    String assinado = assinador.sign(xmlNfe(id), id);
    // remove o bloco KeyInfo (tolerante a prefix de namespace) → sem key para validate
    String semKeyInfo = assinado.replaceAll("(?s)<(\\w+:)?KeyInfo>.*?</(\\w+:)?KeyInfo>", "");
    assertThatThrownBy(() -> validator.validate(semKeyInfo))
        .isInstanceOf(DigitalSignatureException.class);
  }
}
