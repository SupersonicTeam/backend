package br.com.agrocontabil.sefaz;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Texto livre que vai para dentro do XML do evento (issue #128). */
class MensagensSefazTest {

  @Test
  @DisplayName("Justificativa: escapa XML, tira caracteres de controle e quebras de linha")
  void escapar() {
    assertThat(MensagensSefaz.escapar("Valor <errado> & \"duplicado\" d'agua"))
        .isEqualTo("Valor &lt;errado&gt; &amp; &quot;duplicado&quot; d&apos;agua");
    assertThat(MensagensSefaz.escapar("Erro\u0001 de\r\n  digitacao\tna nota\u0000"))
        .isEqualTo("Erro de digitacao na nota");
    assertThat(MensagensSefaz.escapar(null)).isEmpty();
  }
}
