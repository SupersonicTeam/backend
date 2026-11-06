package br.com.agrocontabil.assistente;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.agrocontabil.assistente.GlossarioProdutor.Termo;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Glossário do produtor (#267): carga do YAML, regras do conteúdo e verificação de siglas. */
class GlossarioProdutorTest {

  private static final GlossarioProdutor GLOSSARIO = GlossarioProdutor.padrao();

  @Test
  @DisplayName("Traz as siglas da issue (IBS, CBS, ADCT, LC, EC, PIS, Cofins, ISS, ICMS) e os "
      + "termos do dia a dia")
  void termosObrigatorios() {
    assertThat(GLOSSARIO.termos()).extracting(Termo::termo).contains("IBS", "CBS", "ADCT", "LC",
        "EC", "PIS", "Cofins", "ISS", "ICMS", "NF-e", "DANFE", "NCM", "CFOP", "Funrural", "SEFAZ",
        "cClassTrib", "crédito", "apuração", "split payment", "guia");
    assertThat(GLOSSARIO.termos()).extracting(Termo::termo).doesNotHaveDuplicates();
  }

  @Test
  @DisplayName("Definição em linguagem de produtor: curta, sem número e sem artigo de lei")
  void definicoesCurtasSemNumeroNemArtigo() {
    for (Termo t : GLOSSARIO.termos()) {
      assertThat(t.explicacao()).as(t.termo()).isNotBlank().hasSizeLessThanOrEqualTo(170)
          .doesNotContainPattern("\\d").doesNotContainPattern("(?i)\\bart(igo)?s?\\b\\.?")
          .doesNotContain("§").doesNotEndWith(".").doesNotContain("  ");
      assertThat(Character.isLowerCase(t.explicacao().charAt(0))).as(t.termo()).isTrue();
    }
  }

  @Test
  @DisplayName("O padrão de cada termo acha o próprio termo e não acha pedaço de outra palavra")
  void padraoAchaOTermo() {
    for (Termo t : GLOSSARIO.termos()) {
      assertThat(t.padrao().matcher("sobre " + t.termo() + " aqui").find()).as(t.termo())
          .isTrue();
      assertThat(t.padrao().matcher("xx" + t.termo() + "xx").find()).as(t.termo()).isFalse();
    }
  }

  @Test
  @DisplayName("Sigla sem explicação no texto é apontada; com a forma por extenso, não")
  void semExplicacao() {
    assertThat(GLOSSARIO.semExplicacao("O IBS e a CBS entram na NF-e.")).extracting(Termo::termo)
        .containsExactly("IBS", "CBS", "NF-e");
    assertThat(GLOSSARIO.semExplicacao(
        "O IBS (imposto sobre bens e serviços) e a CBS (contribuição sobre bens e serviços)."))
        .isEmpty();
    // Sem acento e sem caixa: a comparação não depende de como o texto foi escrito.
    assertThat(GLOSSARIO.semExplicacao("IBS, o IMPOSTO SOBRE BENS E SERVICOS")).isEmpty();
    // A própria definição do glossário conta como explicação.
    Termo ibs = GLOSSARIO.termos().stream().filter(t -> t.termo().equals("IBS")).findFirst()
        .orElseThrow();
    assertThat(GLOSSARIO.semExplicacao("IBS: " + ibs.explicacao())).extracting(Termo::termo)
        .doesNotContain("IBS");
    assertThat(GLOSSARIO.semExplicacao("Nada de siglas aqui.")).isEmpty();
    assertThat(GLOSSARIO.semExplicacao(null)).isEmpty();
  }

  @Test
  @DisplayName("Termo inválido derruba a carga: sem definição ou com padrão que não compila")
  void cargaInvalida() {
    assertThatThrownBy(() -> carregar("termos:\n  - termo: XYZ\n    padrao: '\\bXYZ\\b'\n"))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("XYZ");
    assertThatThrownBy(() -> carregar(
        "termos:\n  - termo: XYZ\n    padrao: '(['\n    explicacao: algo\n"))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("XYZ");
    assertThat(carregar("termos:\n  - termo: XYZ\n    padrao: '\\bXYZ\\b'\n"
        + "    explicacao: uma sigla qualquer\n").termos()).hasSize(1);
  }

  @Test
  @DisplayName("A explicação traz também os termos que as definições citam, sem repetir nem girar")
  void aExplicarComOsTermosDasDefinicoes() {
    List<Termo> termos = GLOSSARIO.aExplicar("A CBS entra na nota.");

    // A definição da CBS cita PIS e Cofins, e a da Cofins cita o PIS.
    assertThat(termos).extracting(Termo::termo).containsExactly("CBS", "PIS", "Cofins");
    // Com todas as definições no texto, nada fica sem explicação.
    StringBuilder texto = new StringBuilder("A CBS entra na nota.");
    termos.forEach(t -> texto.append(' ').append(t.termo()).append(": ").append(t.explicacao()));
    assertThat(GLOSSARIO.semExplicacao(texto.toString()).stream().map(Termo::termo))
        .doesNotContain("CBS", "PIS", "Cofins");
    assertThat(GLOSSARIO.aExplicar("Sem sigla nenhuma.")).isEmpty();
    // Texto que usa todos os termos: a lista termina e cada termo vem uma vez.
    String tudo = String.join(" ", GLOSSARIO.termos().stream().map(Termo::termo).toList());
    assertThat(GLOSSARIO.aExplicar(tudo)).extracting(Termo::termo).doesNotHaveDuplicates();
  }

  private static GlossarioProdutor carregar(String yaml) {
    return GlossarioProdutor.carregar(
        new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  @DisplayName("Lista de termos é imutável")
  void listaImutavel() {
    List<Termo> termos = GLOSSARIO.termos();
    assertThatThrownBy(termos::clear).isInstanceOf(UnsupportedOperationException.class);
  }
}
