package br.com.agrocontabil.assistente;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.config.avaliacao.ClarezaTexto;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Linha "O que isso quer dizer" da resposta de lei (#269): o que entra, o que o teto deixa de fora
 * e o que nunca é cortado.
 */
class LinhaGlossarioTest {

  @Test
  @DisplayName("Sem termo do glossário, não há linha")
  void semTermoNaoHaLinha() {
    assertThat(LinhaGlossario.para("A venda do milho segue a regra geral.")).isEmpty();
    assertThat(LinhaGlossario.acrescentadaA("A venda do milho segue a regra geral."))
        .isEqualTo("A venda do milho segue a regra geral.");
  }

  @Test
  @DisplayName("#269: o teto vale para os termos comuns, na ordem do glossário")
  void tetoDosTermosComuns() {
    String texto = "A alíquota incide sobre a base de cálculo no fato gerador, e o contribuinte"
        + " com isenção não paga.";

    String linha = LinhaGlossario.para(texto);

    assertThat(linha.lines().filter(l -> l.startsWith("- "))).hasSize(
        LinhaGlossario.MAXIMO_COMUNS);
    assertThat(linha).contains("- alíquota: ", "- base de cálculo: ", "- fato gerador: ")
        .doesNotContain("- contribuinte: ", "- isenção: ");
  }

  @Test
  @DisplayName("#269: a sigla não entra no teto; com cinco siglas, as cinco são explicadas")
  void siglaNuncaEhCortada() {
    String texto = "O IBS e a CBS substituem o ICMS, o ISS e o PIS, conforme a LC 214/2025.";

    String linha = LinhaGlossario.para(texto);

    assertThat(linha).contains("- IBS (imposto sobre bens e serviços): ",
        "- CBS (contribuição sobre bens e serviços): ",
        "- ICMS (imposto sobre circulação de mercadorias e serviços): ",
        "- ISS (imposto sobre serviços): ", "- LC: ");
    // Nenhuma sigla da régua de clareza fica sem a forma por extenso.
    assertThat(ClarezaTexto.medir(texto + "\n\n" + linha).siglasSemExplicacao()).isEmpty();
  }

  @Test
  @DisplayName("#269: com sigla e muitos termos comuns, as siglas ficam e os comuns são cortados")
  void siglasEComunsJuntos() {
    String texto = "O IBS tem alíquota própria, com base de cálculo no fato gerador, e o"
        + " contribuinte no regime regular desconta o crédito presumido.";

    String linha = LinhaGlossario.para(texto);

    assertThat(linha).contains("- IBS (imposto sobre bens e serviços): ", "- alíquota: ",
        "- base de cálculo: ", "- fato gerador: ")
        .doesNotContain("- contribuinte: ", "- regime regular: ", "- crédito presumido: ");
    // Três comuns, no teto; as siglas que a definição do IBS cita (ICMS, ISS) entram, como antes.
    assertThat(itensDeTermoComum(linha)).hasSize(LinhaGlossario.MAXIMO_COMUNS);
    assertThat(linha).contains("- ICMS (imposto sobre circulação de mercadorias e serviços): ",
        "- ISS (imposto sobre serviços): ");
  }

  @Test
  @DisplayName("#269: a linha não traz número nem artigo de lei, e vem em outro parágrafo")
  void linhaSemNumeroEmOutroParagrafo() {
    String texto = "A alíquota do IBS é reduzida (LC 214/2025, art. 138).";

    String resposta = LinhaGlossario.acrescentadaA(texto);

    assertThat(resposta).startsWith(texto + "\n\n" + LinhaGlossario.TITULO);
    String linha = resposta.substring(resposta.indexOf(LinhaGlossario.TITULO));
    assertThat(linha).doesNotContainPattern("\\d").doesNotContainIgnoringCase("art.");
  }

  @Test
  @DisplayName("#269: termo que a resposta já explica não entra na linha")
  void termoJaExplicadoNaoEntra() {
    String semExplicar = LinhaGlossario.para("A alíquota é o que muda.");
    // "porcentagem do imposto" é uma das expressões aceitas do termo: a resposta já explicou.
    String explicado = LinhaGlossario.para("A alíquota, a porcentagem do imposto, é o que muda.");

    assertThat(semExplicar).contains("- alíquota: ");
    assertThat(explicado).isEmpty();
  }

  /** Os itens da linha que explicam termo comum (não sigla), pelo rótulo antes dos dois-pontos. */
  private static List<String> itensDeTermoComum(String linha) {
    List<String> comuns = GlossarioProdutor.padrao().termos().stream()
        .filter(t -> !t.sigla()).map(GlossarioProdutor.Termo::termo).toList();
    return linha.lines().filter(l -> l.startsWith("- "))
        .filter(l -> comuns.stream().anyMatch(termo -> l.startsWith("- " + termo + ":")))
        .toList();
  }
}
