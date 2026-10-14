package br.com.agrocontabil.sefaz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.security.cert.X509Certificate;
import javax.security.auth.x500.X500Principal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Autor do evento de cancelamento: certificado ICP-Brasil primeiro, chave depois (issue #128). */
class DocumentoEmitenteTest {

  /** Chave com CNPJ 11.444.777/0001-61 nas posições 7 a 20. */
  private static final String CHAVE_CNPJ = "41261011444777000161550010000001011000000014";
  /** Chave de emitente pessoa física: 000 + CPF 529.982.247-25. */
  private static final String CHAVE_CPF = "41261000052998224725550010000001011000000014";

  private static X509Certificate certificado(String subject) {
    X509Certificate c = mock(X509Certificate.class);
    when(c.getSubjectX500Principal()).thenReturn(new X500Principal(subject));
    return c;
  }

  @Test
  @DisplayName("e-CNPJ e e-CPF ICP-Brasil: o documento vem do CN, depois do ':'")
  void doCertificado() {
    assertThat(DocumentoEmitente.de(
        certificado("CN=FAZENDA EXEMPLO LTDA:11444777000161, OU=AC SOLUTI, O=ICP-Brasil, C=BR"),
        CHAVE_CPF)).isEqualTo("11444777000161");
    assertThat(DocumentoEmitente.de(
        certificado("CN=JOAO DA SILVA:52998224725, O=ICP-Brasil, C=BR"), CHAVE_CNPJ))
        .isEqualTo("52998224725");
  }

  @Test
  @DisplayName("Certificado sem o padrao (ou com documento invalido): vem da chave")
  void semPadraoUsaAChave() {
    assertThat(DocumentoEmitente.de(certificado("CN=NFe Teste AgroContabil, C=BR"), CHAVE_CNPJ))
        .isEqualTo("11444777000161");
    assertThat(DocumentoEmitente.de(certificado("CN=EMPRESA:11111111111111, C=BR"), CHAVE_CPF))
        .isEqualTo("52998224725");
    assertThat(DocumentoEmitente.de(null, CHAVE_CPF)).isEqualTo("52998224725");
  }

  @Test
  @DisplayName("CNPJ valido que comeca com 000 continua CNPJ: os digitos verificadores decidem")
  void cnpjComZerosNaFrente() {
    // 00.000.000/0001-91 e um CNPJ valido; nao e "000" + CPF.
    String chave = "412610" + "00000000000191" + "550010000001011000000014";

    assertThat(DocumentoEmitente.daChave(chave)).isEqualTo("00000000000191");
  }

  @Test
  @DisplayName("Sem documento valido em lugar nenhum: erro, nada vai ao fisco")
  void semDocumento() {
    assertThatThrownBy(() -> DocumentoEmitente.daChave(
        "41261099999999999999550010000001011000000014"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
