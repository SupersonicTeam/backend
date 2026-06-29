package br.com.agrocontabil.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DigitalCertificatePropertiesTest {

  @Test
  void shouldHoldPathPasswordAndAlias() {
    DigitalCertificateProperties props =
        new DigitalCertificateProperties("/certs/nfe.p12", "secret", "nfe");
    assertThat(props.path()).isEqualTo("/certs/nfe.p12");
    assertThat(props.password()).isEqualTo("secret");
    assertThat(props.alias()).isEqualTo("nfe");
  }
}
