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
}
