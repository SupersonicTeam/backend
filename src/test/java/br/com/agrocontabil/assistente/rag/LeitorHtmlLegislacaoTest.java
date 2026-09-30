package br.com.agrocontabil.assistente.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Leitura do HTML do Planalto com o charset certo (issue #156).
 *
 * <p>Motivo: o texto compilado da LC 214/2025 está em windows-1252 e <strong>não declara
 * charset</strong>. Lido como UTF-8 (padrão do jsoup), "º" e "§" viram caractere de substituição e
 * o fatiador perde os parágrafos: no arquivo real, 3.221 dispositivos em vez de 4.711.
 */
class LeitorHtmlLegislacaoTest {

  private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");
  private static final String CORPO = "<p>Art. 138. Ficam reduzidas em 60% as alíquotas.</p>"
      + "<p>§ 1º A redução somente se aplica ao produtor.</p>";

  @TempDir Path dir;

  @Test
  @DisplayName("windows-1252 sem declaracao de charset (caso da LC 214): texto e paragrafo corretos")
  void windows1252SemDeclaracao() throws Exception {
    Path arquivo = gravar("<html><body>" + CORPO + "</body></html>", WINDOWS_1252);

    List<DispositivoLegal> dispositivos =
        new FatiadorDispositivos().fatiar(LeitorHtmlLegislacao.ler(arquivo));

    assertThat(dispositivos).extracting(DispositivoLegal::dispositivo)
        .containsExactly("art. 138", "art. 138, § 1º");
    assertThat(dispositivos.get(0).texto()).contains("alíquotas");
  }

  @Test
  @DisplayName("windows-1252 com a declaracao no meta (caso da EC 132)")
  void windows1252ComDeclaracao() throws Exception {
    Path arquivo = gravar("<html><head><meta http-equiv=\"Content-Type\" "
        + "content=\"text/html; charset=windows-1252\"></head><body>" + CORPO + "</body></html>",
        WINDOWS_1252);

    assertThat(LeitorHtmlLegislacao.ler(arquivo).text()).contains("alíquotas", "§ 1º");
  }

  @Test
  @DisplayName("UTF-8 continua lido como UTF-8")
  void utf8() throws Exception {
    Path arquivo = gravar("<html><body>" + CORPO + "</body></html>", StandardCharsets.UTF_8);

    assertThat(LeitorHtmlLegislacao.charset(Files.readAllBytes(arquivo))).isEqualTo("UTF-8");
    assertThat(LeitorHtmlLegislacao.ler(arquivo).text()).contains("alíquotas", "§ 1º");
  }

  @Test
  @DisplayName("Bytes invalidos como UTF-8 sao lidos como windows-1252")
  void deteccao() {
    assertThat(LeitorHtmlLegislacao.charset("§ 1º".getBytes(WINDOWS_1252)))
        .isEqualTo(LeitorHtmlLegislacao.WINDOWS_1252);
    assertThat(LeitorHtmlLegislacao.charset("Art. 1".getBytes(StandardCharsets.US_ASCII)))
        .isEqualTo("UTF-8");
  }

  private Path gravar(String html, Charset charset) throws Exception {
    Path arquivo = dir.resolve("norma.htm");
    Files.write(arquivo, html.getBytes(charset));
    return arquivo;
  }
}
