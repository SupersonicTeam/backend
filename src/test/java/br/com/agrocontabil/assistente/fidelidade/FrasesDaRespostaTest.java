package br.com.agrocontabil.assistente.fidelidade;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Montagem da entrada da bancada do juiz (#269) a partir da resposta final do chat. */
class FrasesDaRespostaTest {

  private static final FrasesDaResposta.Citacao ART_138 =
      new FrasesDaResposta.Citacao("LC 214/2025", "art. 138", "Art. 138. (da citação)");
  private static final FrasesDaResposta.Citacao PAR_1 =
      new FrasesDaResposta.Citacao("LC 214/2025", "art. 138, § 1º", "§ 1º (da citação)");
  private static final Map<String, String> BANCO = Map.of(
      "LC 214/2025|art. 138", "Art. 138. Ficam reduzidas em 60% as alíquotas.");

  @Test
  @DisplayName("uma frase por referência; a referência sai do texto e vira as passagens")
  void frasesComReferencia() {
    FrasesDaResposta.Montagem m = FrasesDaResposta.montar(
        "Os insumos têm redução de 60% (LC 214/2025, art. 138). Fertilizantes entram na lista "
            + "(LC 214/2025, art. 138; LC 214/2025, art. 138, § 1º).\n\nO que isso quer dizer: "
            + "IBS é o imposto estadual e municipal.",
        List.of(ART_138, PAR_1), BANCO);

    assertThat(m.frases()).hasSize(2);
    assertThat(m.frases().get(0).texto()).isEqualTo("Os insumos têm redução de 60%.");
    assertThat(m.frases().get(0).copias())
        .containsExactly("Art. 138. Ficam reduzidas em 60% as alíquotas.");
    assertThat(m.frases().get(1).texto()).isEqualTo("Fertilizantes entram na lista.");
    // O § 1º nao esta no banco: vem o trecho da propria citacao, e a chave e contada.
    assertThat(m.frases().get(1).copias()).containsExactly(
        "Art. 138. Ficam reduzidas em 60% as alíquotas.", "§ 1º (da citação)");
    assertThat(m.dispositivosAusentes()).containsExactly("LC 214/2025|art. 138, § 1º");
    assertThat(m.semCitacao()).isZero();
  }

  @Test
  @DisplayName("frase sem referência usa a da próxima; sem próxima, a da anterior")
  void fraseSemReferenciaEmpresta() {
    FrasesDaResposta.Montagem m = FrasesDaResposta.montar(
        "A redução vale para insumos. Ela é de 60% (LC 214/2025, art. 138). Vale também para "
            + "defensivos.", List.of(ART_138), BANCO);

    // "Ela é de 60%." nao tem palavra de conteudo: nao vai ao juiz, mas empresta a referencia.
    assertThat(m.frases()).extracting(ConferenciaFidelidadeLei.FraseConferida::texto)
        .containsExactly("A redução vale para insumos.", "Vale também para defensivos.");
    assertThat(m.frases()).allSatisfy(f -> assertThat(f.copias()).hasSize(1));
  }

  @Test
  @DisplayName("sem nenhuma citação as frases ficam de fora; frase sem conteúdo também")
  void semCitacaoOuSemConteudo() {
    FrasesDaResposta.Montagem m = FrasesDaResposta.montar(
        "Sim. A redução vale para insumos (Lei 9.999/2099, art. 1).", List.of(ART_138), BANCO);

    // A referencia que nao e de uma citacao fica no texto e nao marca a frase.
    assertThat(m.frases()).isEmpty();
    assertThat(m.semCitacao()).isEqualTo(1);
  }

  @Test
  @DisplayName("o ponto de \"art. 138\" no meio da frase não a divide")
  void abreviacaoNaoDivide() {
    FrasesDaResposta.Montagem m = FrasesDaResposta.montar(
        "Segundo o art. 138 da lei, a redução é de 60% (LC 214/2025, art. 138).",
        List.of(ART_138), BANCO);

    assertThat(m.frases()).singleElement().extracting(ConferenciaFidelidadeLei.FraseConferida::texto)
        .isEqualTo("Segundo o art. 138 da lei, a redução é de 60%.");
  }
}
