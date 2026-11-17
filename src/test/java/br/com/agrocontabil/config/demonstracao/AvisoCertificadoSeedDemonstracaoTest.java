package br.com.agrocontabil.config.demonstracao;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.config.CertificadoDigitalProperties;
import br.com.agrocontabil.nfe.KeyStoreCertificadoProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * O seed da demonstração confere o certificado antes de semear (#560): sem ele cada nota é
 * recusada na assinatura e a demonstração sobe vazia. Só avisa; nunca imprime senha nem alias.
 *
 * <p>Fora de cobertura de propósito: certificado expirado. Cai no mesmo {@code catch} das causas
 * abaixo (senha errada, alias sem chave), e gerar e versionar um {@code .p12} expirado não
 * compensa o custo.
 */
@ExtendWith(OutputCaptureExtension.class)
class AvisoCertificadoSeedDemonstracaoTest {

  private static final String TEST_P12 = "classpath:certs/nfe-teste.p12";
  private static final String AVISO = "A demonstracao vai subir SEM nota";

  private static KeyStoreCertificadoProvider provider(String path, String senha, String alias) {
    return new KeyStoreCertificadoProvider(new CertificadoDigitalProperties(path, senha, alias));
  }

  @Test
  @DisplayName("certificado que carrega: sem aviso")
  void certificadoValidoNaoAvisa(CapturedOutput saida) {
    SeedDemonstracao.avisarSeCertificadoNaoCarrega(provider(TEST_P12, "changeit", "nfe-teste"));

    assertThat(saida.getOut()).doesNotContain(AVISO);
  }

  @Test
  @DisplayName("caminho inexistente (o placeholder do .env.example): ERROR citando as variaveis")
  void caminhoInexistenteAvisa(CapturedOutput saida) {
    SeedDemonstracao.avisarSeCertificadoNaoCarrega(provider(
        "file:/caminho/seguro/certificado.p12", "senha-secreta", "alias-secreto"));

    assertThat(saida.getOut()).contains("ERROR").contains(AVISO)
        .contains("NFE_CERT_PATH").contains("NFE_CERT_PASSWORD")
        .doesNotContain("senha-secreta").doesNotContain("alias-secreto");
  }

  @Test
  @DisplayName("caminho em branco: ERROR")
  void caminhoEmBrancoAvisa(CapturedOutput saida) {
    SeedDemonstracao.avisarSeCertificadoNaoCarrega(provider("", "x", ""));

    assertThat(saida.getOut()).contains(AVISO);
  }

  @Test
  @DisplayName("senha errada com caminho certo (o caso do CHANGE_ME): ERROR, sem a senha")
  void senhaErradaAvisa(CapturedOutput saida) {
    SeedDemonstracao.avisarSeCertificadoNaoCarrega(
        provider(TEST_P12, "senha-errada", "nfe-teste"));

    assertThat(saida.getOut()).contains(AVISO).contains("NFE_CERT_PASSWORD")
        .doesNotContain("senha-errada");
  }

  @Test
  @DisplayName("alias sem chave privada: ERROR com o alias (esperado: a mensagem do provider o "
      + "cita), sem a senha")
  void aliasSemChavePrivadaAvisa(CapturedOutput saida) {
    SeedDemonstracao.avisarSeCertificadoNaoCarrega(
        provider(TEST_P12, "changeit", "alias-que-nao-existe"));

    assertThat(saida.getOut()).contains(AVISO).contains("alias-que-nao-existe")
        .doesNotContain("changeit");
  }
}
