package br.com.agrocontabil.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CertificadoDigitalPropertiesTest {

  @Test
  void shouldHoldPathPasswordAndAlias() {
    CertificadoDigitalProperties props =
        new CertificadoDigitalProperties("/certs/nfe.p12", "secret", "nfe");
    assertThat(props.path()).isEqualTo("/certs/nfe.p12");
    assertThat(props.password()).isEqualTo("secret");
    assertThat(props.alias()).isEqualTo("nfe");
  }
}
