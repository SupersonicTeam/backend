package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.exception.PendenciaException;
import br.com.agrocontabil.model.PendenciaAnexo;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ConferenciaAnexo — tipo pelos bytes, XML endurecido, nome e hash (#180)")
class ConferenciaAnexoTest {

  @Test
  @DisplayName("reconhece PDF, JPEG, PNG e XML pelos bytes iniciais")
  void reconheceTiposPelosBytes() {
    assertThat(ConferenciaAnexo.mimeConferido("%PDF-1.7\n...".getBytes(StandardCharsets.US_ASCII)))
        .isEqualTo(ConferenciaAnexo.PDF);
    assertThat(ConferenciaAnexo.mimeConferido(
        new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0}))
        .isEqualTo(ConferenciaAnexo.JPEG);
    assertThat(ConferenciaAnexo.mimeConferido(
        new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0}))
        .isEqualTo(ConferenciaAnexo.PNG);
    assertThat(ConferenciaAnexo.mimeConferido(
        "\uFEFF<?xml version=\"1.0\"?><nfeProc/>".getBytes(StandardCharsets.UTF_8)))
        .isEqualTo(ConferenciaAnexo.XML);
    // Sem declaracao, espacos antes da raiz sao permitidos pelo XML.
    assertThat(ConferenciaAnexo.mimeConferido("\n  <nfeProc/>".getBytes(StandardCharsets.UTF_8)))
        .isEqualTo(ConferenciaAnexo.XML);
  }

  @Test
  @DisplayName("recusa executavel, HTML com script, vazio e acima de 5 MB")
  void recusaTiposForaDaLista() {
    assertInvalido(new byte[] {'M', 'Z', (byte) 0x90, 0});
    assertInvalido("GIF89a".getBytes(StandardCharsets.US_ASCII));
    assertInvalido(new byte[0]);
    assertInvalido(null);
    byte[] grande = new byte[PendenciaAnexo.TAMANHO_MAXIMO + 1];
    grande[0] = '%';
    grande[1] = 'P';
    grande[2] = 'D';
    grande[3] = 'F';
    grande[4] = '-';
    assertInvalido(grande);
  }

  @Test
  @DisplayName("recusa XML mal formado e XML com DTD (entidade externa / XXE)")
  void recusaXmlMalFormadoOuComDtd() {
    assertInvalido("<nfe><sem-fechar>".getBytes(StandardCharsets.UTF_8));
    assertInvalido(("<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>"
        + "<x>&e;</x>").getBytes(StandardCharsets.UTF_8));
  }

  @Test
  @DisplayName("nome sem caminho, sem controle nem aspas, nunca vazio, ate 120 caracteres")
  void sanitizaNome() {
    assertThat(ConferenciaAnexo.nomeSanitizado("../../etc/passwd")).isEqualTo("passwd");
    assertThat(ConferenciaAnexo.nomeSanitizado("C:\\Users\\x\\nota.pdf")).isEqualTo("nota.pdf");
    assertThat(ConferenciaAnexo.nomeSanitizado("a\"b\r\n.pdf")).isEqualTo("ab.pdf");
    assertThat(ConferenciaAnexo.nomeSanitizado(null)).isEqualTo("anexo");
    assertThat(ConferenciaAnexo.nomeSanitizado("..")).isEqualTo("anexo");
    String longo = "x".repeat(200) + ".pdf";
    assertThat(ConferenciaAnexo.nomeSanitizado(longo)).hasSize(ConferenciaAnexo.NOME_MAXIMO)
        .endsWith(".pdf");
  }

  @Test
  @DisplayName("SHA-256 em hexadecimal")
  void sha256() {
    assertThat(ConferenciaAnexo.sha256("abc".getBytes(StandardCharsets.US_ASCII)))
        .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
  }

  private static void assertInvalido(byte[] bytes) {
    assertThatThrownBy(() -> ConferenciaAnexo.mimeConferido(bytes))
        .isInstanceOf(PendenciaException.class)
        .satisfies(e -> assertThat(((PendenciaException) e).getErrors().get(0).code())
            .isEqualTo(ErrorCode.ANEXO_INVALIDO.name()));
  }
}
