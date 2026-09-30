package br.com.agrocontabil.assistente.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Bordas do {@link FatiadorDispositivos} (achados do code review da issue #156; texto ilustrativo). */
class FatiadorDispositivosBordasTest {

  private final FatiadorDispositivos fatiador = new FatiadorDispositivos();

  @Test
  @DisplayName("Aspas fechadas seguidas de qualquer anotacao encerram a citacao")
  void aspasComAnotacaoNaoPrevista() {
    List<DispositivoLegal> d = fatiador.fatiar("""
        <p>Art. 1º A norma de referência passa a vigorar com as seguintes alterações:</p>
        <p>“Art. 5º Texto novo do artigo citado.” (NR) (Vide Norma Ilustrativa nº 9)</p>
        <p>Art. 2º Esta norma entra em vigor na data de sua publicação.</p>
        """);

    assertThat(d).extracting(DispositivoLegal::dispositivo)
        .containsExactly("art. 1º", "art. 1º (redação do art. 5º)", "art. 2º");
  }

  @Test
  @DisplayName("Parentese no fim de linha comum e texto de lei: nao some")
  void parenteseFinalPreservado() {
    List<DispositivoLegal> d = fatiador.fatiar("""
        <p>Art. 1º A alíquota é reduzida em 60% (sessenta por cento).</p>
        """);

    assertThat(d).singleElement()
        .satisfies(x -> assertThat(x.texto()).endsWith("60% (sessenta por cento)."));
  }

  @Test
  @DisplayName("Palavra de letras romanas nao vira inciso")
  void palavraRomanaNaoEInciso() {
    List<DispositivoLegal> d = fatiador.fatiar("""
        <p>Art. 1º Aplica-se o seguinte:</p>
        <p>CIVIL - disposição de exemplo sem numeração.</p>
        <p>IV - inciso de verdade.</p>
        """);

    assertThat(d).extracting(DispositivoLegal::dispositivo)
        .containsExactly("art. 1º", "art. 1º, inciso IV");
    assertThat(d.get(0).texto()).contains("CIVIL - disposição");
  }

  @Test
  @DisplayName("Artigo citado sem aspa de abertura (salto na numeracao) continua sendo citacao")
  void artigoCitadoSemAspaDeAbertura() {
    // Formato da LC 227/2026: o Planalto omite a aspa antes do "Art. 493-A" inserido na LC 214.
    List<DispositivoLegal> dispositivos = new FatiadorDispositivos().fatiar("""
        <html><body>
        <p>Art. 174. A Lei Complementar nº 214, de 2025, passa a vigorar com as alterações:</p>
        <p>“Art. 26. O regime aplica-se às cooperativas.” (NR)</p>
        <p>Art. 493-A. É instituída associação pública especial.</p>
        <p>§ 1º A associação tem sede no Distrito Federal.” (NR)</p>
        <p>Art. 175. Esta Lei Complementar entra em vigor na data de sua publicação.</p>
        </body></html>
        """);

    assertThat(dispositivos).extracting(DispositivoLegal::dispositivo).containsExactly(
        "art. 174",
        "art. 174 (redação do art. 26)",
        "art. 174 (redação do art. 493-A)",
        "art. 174 (redação do art. 493-A, § 1º)",
        "art. 175");
  }
}
