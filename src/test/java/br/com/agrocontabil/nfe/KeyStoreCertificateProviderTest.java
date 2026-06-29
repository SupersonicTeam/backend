package br.com.agrocontabil.nfe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.agrocontabil.config.DigitalCertificateProperties;
import br.com.agrocontabil.exception.DigitalSignatureException;
import org.junit.jupiter.api.Test;

class KeyStoreCertificateProviderTest {

  private static final String TEST_P12 = "classpath:certs/nfe-teste.p12";

  @Test
  void shouldLoadPrivateKeyAndCertificate() {
    var props = new DigitalCertificateProperties(TEST_P12, "changeit", "nfe-teste");
    var provider = new KeyStoreCertificateProvider(props);

    assertThat(provider.getPrivateKey()).isNotNull();
    assertThat(provider.getCertificate()).isNotNull();
    assertThat(provider.getCertificate().getSubjectX500Principal().getName())
        .contains("NFe Teste");
  }

  @Test
  void shouldFailWithCertificadoAusenteWhenPathMissing() {
    var props = new DigitalCertificateProperties("classpath:certs/nao-existe.p12",
        "changeit", "nfe-teste");
    var provider = new KeyStoreCertificateProvider(props);

    assertThatThrownBy(provider::getCertificate)
        .isInstanceOf(DigitalSignatureException.class);
  }

  @Test
  void shouldFailWithCertificadoInvalidoWhenWrongPassword() {
    var props = new DigitalCertificateProperties(TEST_P12, "senha-errada", "nfe-teste");
    var provider = new KeyStoreCertificateProvider(props);

    assertThatThrownBy(provider::getPrivateKey)
        .isInstanceOf(DigitalSignatureException.class);
  }

  @Test
  void shouldFailWhenPathBlank() {
    var props = new DigitalCertificateProperties("", "changeit", "nfe-teste");
    var provider = new KeyStoreCertificateProvider(props);

    assertThatThrownBy(provider::getCertificate)
        .isInstanceOf(DigitalSignatureException.class);
  }
}
