package br.com.agrocontabil.nfe;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.agrocontabil.config.CertificadoDigitalProperties;
import br.com.agrocontabil.exception.AssinaturaDigitalException;
import org.junit.jupiter.api.Test;

class AssinaturaValidatorTest {

  private final KeyStoreCertificadoProvider provider = new KeyStoreCertificadoProvider(
      new CertificadoDigitalProperties("classpath:certs/nfe-teste.p12", "changeit", "nfe-teste"));
  private final NfeAssinador assinador = new NfeAssinador(provider);
  private final AssinaturaValidator validator = new AssinaturaValidator();

  private String xmlNfe(String id) {
    return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        + "<NFe xmlns=\"http://www.portalfiscal.inf.br/nfe\">"
        + "<infNFe Id=\"" + id + "\" versao=\"4.00\"><ide><cUF>41</cUF></ide></infNFe>"
        + "</NFe>";
  }

  @Test
  void validSignatureShouldPass() {
    String id = "NFe41250600000000000000000000000000000000000010";
    String assinado = assinador.assinar(xmlNfe(id), id);
    assertThatCode(() -> validator.validar(assinado)).doesNotThrowAnyException();
  }

  @Test
  void tamperedXmlShouldFail() {
    String id = "NFe41250600000000000000000000000000000000000011";
    String assinado = assinador.assinar(xmlNfe(id), id);
    // adultera o conteudo assinado (troca cUF) apos a assinatura
    String adulterado = assinado.replace("<cUF>41</cUF>", "<cUF>35</cUF>");
    assertThatThrownBy(() -> validator.validar(adulterado))
        .isInstanceOf(AssinaturaDigitalException.class);
  }

  @Test
  void xmlWithoutSignatureShouldFail() {
    String id = "NFe41250600000000000000000000000000000000000012";
    assertThatThrownBy(() -> validator.validar(xmlNfe(id)))
        .isInstanceOf(AssinaturaDigitalException.class);
  }
}
