package br.com.agrocontabil.nfe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.agrocontabil.config.CertificadoDigitalProperties;
import br.com.agrocontabil.exception.AssinaturaDigitalException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
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

  @Test
  @DisplayName("certificado autoassinado (o de teste) gera WARN visivel ja na subida (#225)")
  void certificadoDeTesteAvisaNaSubida(CapturedOutput saida) {
    var provider = new KeyStoreCertificadoProvider(
        new CertificadoDigitalProperties(TEST_P12, "changeit", "nfe-teste"));

    provider.conferirNaSubida();

    assertThat(saida.getOut()).contains("WARN").contains("autoassinado ou fora da ICP-Brasil")
        .doesNotContain("changeit");
    assertThat(provider.getCertificate()).isNotNull();
  }

  @Test
  @DisplayName("na subida, certificado ausente ou ilegivel so avisa: a aplicacao sobe e a emissao"
      + " falha quando pedida")
  void subidaNaoQuebraSemCertificado(CapturedOutput saida) {
    new KeyStoreCertificadoProvider(new CertificadoDigitalProperties("", "", ""))
        .conferirNaSubida();
    var ilegivel = new KeyStoreCertificadoProvider(
        new CertificadoDigitalProperties(TEST_P12, "senha-errada", "nfe-teste"));
    ilegivel.conferirNaSubida();

    assertThat(saida.getOut()).contains("nao carregou").doesNotContain("senha-errada");
    assertThatThrownBy(ilegivel::getCertificate).isInstanceOf(AssinaturaDigitalException.class);
  }

  private static final String AVISO_DEMO = "a demonstracao NAO vai gerar nota";

  @Test
  @DisplayName("demonstracao ligada + caminho inexistente = ERROR, sem senha nem alias (#560)")
  void demonstracaoComCaminhoInexistenteLogaError(CapturedOutput saida) {
    var props = new CertificadoDigitalProperties("file:/caminho/seguro/certificado.p12",
        "senha-secreta", "alias-secreto");
    new KeyStoreCertificadoProvider(props, true).conferirNaSubida();

    assertThat(saida.getOut()).contains("ERROR").contains(AVISO_DEMO)
        .doesNotContain("senha-secreta").doesNotContain("alias-secreto");
  }

  @Test
  @DisplayName("demonstracao ligada + caminho em branco = ERROR (#560)")
  void demonstracaoComCaminhoEmBrancoLogaError(CapturedOutput saida) {
    new KeyStoreCertificadoProvider(new CertificadoDigitalProperties("", "x", ""), true)
        .conferirNaSubida();

    assertThat(saida.getOut()).contains(AVISO_DEMO);
  }

  @Test
  @DisplayName("demonstracao ligada + arquivo ilegivel (senha errada) = ERROR (#560)")
  void demonstracaoComArquivoIlegivelLogaError(CapturedOutput saida) {
    new KeyStoreCertificadoProvider(
        new CertificadoDigitalProperties(TEST_P12, "senha-errada", "nfe-teste"), true)
        .conferirNaSubida();

    assertThat(saida.getOut()).contains(AVISO_DEMO).doesNotContain("senha-errada");
  }

  @Test
  @DisplayName("demonstracao ligada + certificado existente = sem o ERROR da demonstracao (#560)")
  void demonstracaoComCaminhoExistenteNaoLogaError(CapturedOutput saida) {
    new KeyStoreCertificadoProvider(
        new CertificadoDigitalProperties(TEST_P12, "changeit", "nfe-teste"), true)
        .conferirNaSubida();

    assertThat(saida.getOut()).doesNotContain(AVISO_DEMO);
  }

  @Test
  @DisplayName("demonstracao desligada + caminho inexistente = so o WARN de sempre (#560)")
  void semDemonstracaoCaminhoInexistenteNaoLogaErrorDaDemonstracao(CapturedOutput saida) {
    new KeyStoreCertificadoProvider(
        new CertificadoDigitalProperties("file:/nao/existe.p12", "x", ""), false)
        .conferirNaSubida();

    assertThat(saida.getOut()).doesNotContain(AVISO_DEMO).contains("nao carregou na subida");
  }
}
