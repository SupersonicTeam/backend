package br.com.agrocontabil.config.avaliacao;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.assistente.GlossarioProdutor;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Medidas automáticas de clareza (issue #264). */
class ClarezaTextoTest {

  @Test
  @DisplayName("Régua fixa (#264): só as 8 siglas, com as explicações aceitas desde o início")
  void reguaFixaDeOitoSiglas() {
    assertThat(ClarezaTexto.SIGLAS_DA_REGUA).containsExactly(
        "IBS", "CBS", "NF-e", "NCM", "CFOP", "Funrural", "DANFE", "SEFAZ");
    // Cada sigla sozinha conta; cada explicação aceita pela régua original a resolve.
    Map<String, List<String>> aceitas = Map.of(
        "IBS", List.of("imposto sobre bens e serviços"),
        "CBS", List.of("contribuição sobre bens e serviços",
            "contribuição social sobre bens e serviços"),
        "NF-e", List.of("nota fiscal eletrônica"),
        "NCM", List.of("nomenclatura comum do mercosul", "código do produto",
            "código de classificação do produto"),
        "CFOP", List.of("código fiscal de operações", "código da operação",
            "código fiscal da operação"),
        "Funrural", List.of("contribuição previdenciária", "previdência rural",
            "previdência do produtor", "INSS"),
        "DANFE", List.of("documento auxiliar"),
        "SEFAZ", List.of("secretaria da fazenda", "secretaria de fazenda",
            "secretaria de estado de fazenda", "secretaria de estado da fazenda",
            "secretaria estadual da fazenda"));
    aceitas.forEach((sigla, explicacoes) -> {
      assertThat(ClarezaTexto.medir("Veja o " + sigla + ".").siglasSemExplicacao())
          .as(sigla).containsExactly(sigla);
      explicacoes.forEach(e -> assertThat(
          ClarezaTexto.medir("Veja o " + sigla + ", " + e + ".").siglasSemExplicacao())
          .as(sigla + " / " + e).isEmpty());
    });
  }

  @Test
  @DisplayName("Régua da Ceres 1: a definição do glossário sozinha não explica a sigla")
  void definicaoDoGlossarioNaoContaNaReguaDaMedicao() {
    // "imposto dos municípios sobre serviços" é a definição do ISS e "o imposto..." a do IBS;
    // aqui só a definição (sem o nome nem as expressões aceitas) aparece junto da sigla.
    String definicaoDaCbs = GlossarioProdutor.padrao().termos().stream()
        .filter(t -> t.termo().equals("CBS")).findFirst().orElseThrow().explicacao();
    assertThat(ClarezaTexto.medir("A CBS: " + definicaoDaCbs + ".").siglasSemExplicacao())
        .containsExactly("CBS");
    // Já a composição e os testes de texto fixo aceitam a definição.
    assertThat(GlossarioProdutor.padrao().semExplicacao("A CBS: " + definicaoDaCbs + "."))
        .extracting(GlossarioProdutor.Termo::termo).doesNotContain("CBS");
  }

  @Test
  @DisplayName("Sigla do glossário fora da régua (LC, PIS, ICMS...) não entra na medição")
  void siglaForaDaReguaNaoConta() {
    assertThat(ClarezaTexto.medir("Conforme a LC 214/2025, o PIS e o ICMS saem.")
        .siglasSemExplicacao()).isEmpty();
  }

  @Test
  @DisplayName("Frases e palavras: abreviação e valor em reais não quebram a frase")
  void frasesEPalavras() {
    ClarezaTexto.Medida m = ClarezaTexto.medir(
        "Voce comprou R$ 12.500,00 de adubo. Pelo art. 138 da lei, ha reducao!\nConfira.");

    assertThat(m.frases()).isEqualTo(3);
    assertThat(m.palavras()).isEqualTo(14);
    assertThat(m.palavrasPorFrase()).isEqualTo(14 / 3.0);
  }

  @Test
  @DisplayName("Item numerado não vira frase; CBS pelo nome da lei conta como explicada")
  void listaENomeLegal() {
    ClarezaTexto.Medida m = ClarezaTexto.medir("1. Emita a nota\n2. Pague a guia");

    assertThat(m.frases()).isEqualTo(2);
    assertThat(m.palavrasPorFrase()).isEqualTo(3.0);
    assertThat(ClarezaTexto.medir("A Contribuição Social sobre Bens e Serviços (CBS) vale.")
        .siglasSemExplicacao()).isEmpty();
  }

  @Test
  @DisplayName("Texto vazio ou nulo mede zero")
  void vazio() {
    assertThat(ClarezaTexto.medir(null).frases()).isZero();
    assertThat(ClarezaTexto.medir("  ").palavrasPorFrase()).isZero();
  }

  @Test
  @DisplayName("Sigla sem explicação conta; com a explicação em qualquer lugar, não")
  void siglas() {
    assertThat(ClarezaTexto.medir("O IBS e a CBS entram na NF-e.").siglasSemExplicacao())
        .containsExactly("IBS", "CBS", "NF-e");
    assertThat(ClarezaTexto.medir(
        "O IBS (Imposto sobre Bens e Serviços) vai na nota fiscal eletrônica, a NFe.")
        .siglasSemExplicacao()).isEmpty();
    // "CBS" dentro de outra palavra não é a sigla; funrural vale sem caixa.
    assertThat(ClarezaTexto.medir("ABCBS e o funrural.").siglasSemExplicacao())
        .containsExactly("Funrural");
  }
}
