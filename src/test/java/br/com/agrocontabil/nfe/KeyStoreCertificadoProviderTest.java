package br.com.agrocontabil.nfe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.agrocontabil.config.CertificadoDigitalProperties;
import br.com.agrocontabil.exception.AssinaturaDigitalException;
import org.junit.jupiter.api.Test;

class KeyStoreCertificadoProviderTest {

  private static final String TEST_P12 = "classpath:certs/nfe-teste.p12";

  @Test
  void shouldLoadPrivateKeyAndCertificate() {
    var props = new CertificadoDigitalProperties(TEST_P12, "changeit", "nfe-teste");
    var provider = new KeyStoreCertificadoProvider(props);

    assertThat(provider.getPrivateKey()).isNotNull();
    assertThat(provider.getCertificate()).isNotNull();
    assertThat(provider.getCertificate().getSubjectX500Principal().getName())
        .contains("NFe Teste");
  }

  @Test
  void shouldFailWithCertificadoAusenteWhenPathMissing() {
    var props = new CertificadoDigitalProperties("classpath:certs/nao-existe.p12",
        "changeit", "nfe-teste");
    var provider = new KeyStoreCertificadoProvider(props);

    assertThatThrownBy(provider::getCertificate)
        .isInstanceOf(AssinaturaDigitalException.class);
  }

  @Test
  void shouldFailWithCertificadoInvalidoWhenWrongPassword() {
    var props = new CertificadoDigitalProperties(TEST_P12, "senha-errada", "nfe-teste");
    var provider = new KeyStoreCertificadoProvider(props);

    assertThatThrownBy(provider::getPrivateKey)
        .isInstanceOf(AssinaturaDigitalException.class);
  }

  @Test
  void shouldFailWhenPathBlank() {
    var props = new CertificadoDigitalProperties("", "changeit", "nfe-teste");
    var provider = new KeyStoreCertificadoProvider(props);

    assertThatThrownBy(provider::getCertificate)
        .isInstanceOf(AssinaturaDigitalException.class);
  }
}
