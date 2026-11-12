package br.com.agrocontabil.assistente.fidelidade;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Níveis do dispositivo no texto do trecho (#269): onde começam o parágrafo, o inciso e a alínea, e
 * o ancestral que uma passagem toca.
 */
class NivelDispositivoTest {

  private static final String CAPUT = "Art. 10. A regra geral vale para todos.";
  private static final String PARAGRAFO = "§ 2º O disposto no § 2º-A e no caput aplica-se:";
  private static final String INCISO = "IV - aos insumos;";

  @Test
  @DisplayName("remissão no meio do texto do parágrafo não é o começo dele")
  void remissaoNaoEhComecoDeNivel() {
    String texto = CAPUT + " " + PARAGRAFO + " " + INCISO;
    NivelDispositivo niveis = NivelDispositivo.de("art. 10, § 2º, inciso IV", texto);

    assertThat(niveis).isNotNull();
    // "aplica-se" esta no texto proprio do paragrafo, depois da remissao ao "§ 2º-A".
    NivelDispositivo.Ancestral ancestral = niveis.ancestralDe(texto.indexOf("aplica-se"));
    assertThat(ancestral.dispositivo()).isEqualTo("art. 10, § 2º");
    assertThat(ancestral.texto()).isEqualTo(CAPUT + " " + PARAGRAFO);
    assertThat(niveis.ancestralDe(texto.indexOf("regra")).dispositivo()).isEqualTo("art. 10");
    assertThat(niveis.ancestralDe(texto.indexOf("insumos"))).isNull();
  }

  @Test
  @DisplayName("parágrafo único, inciso com letra e alínea")
  void formasDoRotulo() {
    String unico = "Art. 3º Fica criado o regime. Parágrafo único. O regime vale a partir de 2027.";
    NivelDispositivo n1 = NivelDispositivo.de("art. 3º, parágrafo único", unico);
    assertThat(n1.ancestralDe(unico.indexOf("criado")).dispositivo()).isEqualTo("art. 3º");

    String comLetra = "Art. 5º-A São isentos: I-A - os ovos; a) frescos.";
    NivelDispositivo n2 = NivelDispositivo.de("art. 5º-A, inciso I-A, alínea a", comLetra);
    assertThat(n2).isNotNull();
    assertThat(n2.ancestralDe(comLetra.indexOf("ovos")).dispositivo())
        .isEqualTo("art. 5º-A, inciso I-A");
    assertThat(n2.ancestralDe(comLetra.indexOf("frescos"))).isNull();
  }

  @Test
  @DisplayName("sem o começo de algum nível, ou rótulo fora da forma simples: nulo")
  void semNivelOuRotuloComplexo() {
    // O caput nao termina em pontuacao: o paragrafo nao e achado e a citacao nao muda.
    assertThat(NivelDispositivo.de("art. 10, § 2º", "Art. 10 A regra § 2º O resto.")).isNull();
    assertThat(NivelDispositivo.de("Anexo IX, item 5", "ANEXO IX. Item 5: adubos")).isNull();
    assertThat(NivelDispositivo.de("art. 10 [2]", CAPUT)).isNull();
    assertThat(NivelDispositivo.de(null, CAPUT)).isNull();
    assertThat(NivelDispositivo.de("art. 10", null)).isNull();
    // Caput sozinho: nada acima dele.
    assertThat(NivelDispositivo.de("art. 10", CAPUT).ancestralDe(5)).isNull();
  }
}
