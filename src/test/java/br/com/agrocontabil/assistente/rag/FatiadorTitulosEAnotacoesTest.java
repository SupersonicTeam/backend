package br.com.agrocontabil.assistente.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * O nome de seção, subseção, capítulo, título ou livro nunca é anexado ao dispositivo anterior, e as
 * anotações do consolidado do Planalto sem parênteses ("Produção de efeitos", "Vigência", "Sem
 * eficácia", "Vide ...") não entram no texto (issue #606).
 *
 * <p>Os arquivos {@code legislacao/planalto-<norma>-trechos-reais.html} reúnem recortes <strong>literais</strong>
 * do HTML do Planalto (um caso de cada norma da base: LC 214/2025, LC 227/2026, Lei 8.212/1991 e
 * EC 132/2023), com a estrutura que o produz: o rótulo ("Seção VII") e o nome ("Dos Alimentos...")
 * em parágrafos separados, o rótulo do capítulo da Lei 8.212 fora de {@code <p>} e o link de
 * anotação no fim do caput.
 */
class FatiadorTitulosEAnotacoesTest {

  private static Map<String, String> lc214;
  private static Map<String, String> lc227;
  private static Map<String, String> lei8212;
  private static Map<String, String> ec132;

  @BeforeAll
  static void fatiar() throws IOException {
    lc214 = fatiarArquivo("lc214");
    lc227 = fatiarArquivo("lc227");
    lei8212 = fatiarArquivo("lei8212");
    ec132 = fatiarArquivo("ec132");
  }

  private static Map<String, String> fatiarArquivo(String norma) throws IOException {
    String html = new ClassPathResource("legislacao/planalto-" + norma + "-trechos-reais.html")
        .getContentAsString(StandardCharsets.UTF_8);
    Map<String, String> mapa = new LinkedHashMap<>();
    for (DispositivoLegal d : new FatiadorDispositivos().fatiar(html)) {
      mapa.putIfAbsent(d.dispositivo(), d.texto());
    }
    return mapa;
  }

  private static String de(Map<String, String> norma, String dispositivo) {
    assertThat(norma).containsKey(dispositivo);
    return norma.get(dispositivo);
  }

  @Test
  @DisplayName("LC 214: o nome da seção seguinte não é colado ao fim do artigo (art. 134, 135, 136)")
  void lc214NomeDeSecaoNaoVaiParaOArtigoAnterior() {
    assertThat(de(lc214, "art. 134")).endsWith("já contempladas.");
    assertThat(de(lc214, "art. 135")).endsWith("classificações da NCM/SH.");
    assertThat(de(lc214, "art. 136")).endsWith("classificações da NCM/SH.");
    assertThat(lc214.values()).noneMatch(t -> t.contains("Dos Alimentos Destinados ao Consumo"))
        .noneMatch(t -> t.contains("Dos Produtos de Higiene Pessoal"))
        .noneMatch(t -> t.contains("Dos Produtos Agropecuários, Aquícolas"));
    assertThat(de(lc214, "art. 137")).startsWith("Art. 137.");
  }

  @Test
  @DisplayName("LC 214: a anotação 'Produção de efeitos' não fica no meio do artigo (art. 35 e art. 169)")
  void lc214AnotacaoProducaoDeEfeitos() {
    assertThat(de(lc214, "art. 169")).endsWith("seja inscrito como MEI.");
    assertThat(de(lc214, "art. 169, § 1º")).doesNotContain("Produção");
    assertThat(de(lc214, "art. 169, § 1º, inciso I")).doesNotContain("Produção")
        .contains("O contribuinte de IBS e de CBS sujeito ao regime regular poderá apropriar")
        .contains("somente se aplicam ao contribuinte que adquire bens");
    assertThat(de(lc214, "art. 35")).endsWith("split payment.");
    assertThat(de(lc214, "art. 35, § 1º")).doesNotContain("Produção de efeitos");
  }

  @Test
  @DisplayName("LC 227: nome do título e do capítulo (maiúsculas) não vão para o art. 102")
  void lc227TituloECapitulo() {
    assertThat(de(lc227, "art. 102")).endsWith("neste Título.");
    assertThat(lc227.values()).noneMatch(t -> t.contains("DA DISTRIBUIÇÃO DO PRODUTO"))
        .noneMatch(t -> t.contains("DISPOSIÇÕES PRELIMINARES"));
    assertThat(de(lc227, "art. 103")).startsWith("Art. 103.");
  }

  @Test
  @DisplayName("Lei 8.212: rótulo do capítulo fora de <p> e nomes do capítulo e da seção")
  void lei8212CapituloForaDeParagrafo() {
    assertThat(lei8212.keySet()).anyMatch(d -> d.startsWith("art. 11, parágrafo único"));
    assertThat(lei8212.values()).noneMatch(t -> t.contains("DOS CONTRIBUINTES"))
        .noneMatch(t -> t.contains("Dos Segurados"));
    assertThat(de(lei8212, "art. 12")).startsWith("Art. 12. São segurados obrigatórios");
  }

  @Test
  @DisplayName("Lei 8.212: a tabela do art. 20 e a nota 'Valores e alíquotas' ficam (não são título)")
  void lei8212TabelaEmCelulaCentralizadaNaoEhTitulo() {
    assertThat(de(lei8212, "art. 20")).contains("Salário-de-contribuição", "Alíquota em %", "249,80",
        "(Valores e alíquotas dados pela Lei nº 9.129, de 20.11.95)");
  }

  @Test
  @DisplayName("Lei 8.212: o link 'Vigência' não fica no fim do inciso do art. 22")
  void lei8212AnotacaoVigencia() {
    assertThat(lei8212.keySet()).contains("art. 22, inciso I");
    assertThat(de(lei8212, "art. 22, inciso I")).doesNotContain("Vigência")
        .contains("vinte por cento sobre o total das remunerações");
  }

  @Test
  @DisplayName("EC 132: rótulo e nome da seção dentro da citação não vão para o inciso anterior")
  void ec132NomeDeSecaoDentroDaCitacao() {
    assertThat(ec132.values())
        .noneMatch(t -> t.contains("Do Imposto de Competência Compartilhada entre Estados"));
    assertThat(ec132.keySet()).anyMatch(d -> d.startsWith("art. 1º (redação do art. 156-A"));
    assertThat(de(ec132, "art. 11")).endsWith("12 de novembro de 2019.");
  }

  @Test
  @DisplayName("Nome de seção em parágrafo próprio, logo depois do rótulo, sai do texto do dispositivo")
  void nomeDepoisDoRotuloEmParagrafoProprio() {
    List<DispositivoLegal> r = new FatiadorDispositivos().fatiar("""
        <p>Art. 1º Texto do primeiro artigo.</p>
        <p align="center"><b>Seção II</b></p>
        <p align="center">Dos Medicamentos</p>
        <p>Art. 2º Texto do segundo artigo.</p>
        """);
    assertThat(r).extracting(DispositivoLegal::dispositivo).containsExactly("art. 1º", "art. 2º");
    assertThat(r.get(0).texto()).isEqualTo("Art. 1º Texto do primeiro artigo.");
  }

  @Test
  @DisplayName("Rótulo sem nome: o dispositivo seguinte não é engolido")
  void rotuloSemNomeNaoEngoleODispositivo() {
    List<DispositivoLegal> r = new FatiadorDispositivos().fatiar("""
        <p>Art. 1º Texto do primeiro artigo.</p>
        <p align="center"><b>CAPÍTULO ÚNICO</b></p>
        <p>Art. 2º Texto do segundo artigo.</p>
        <p align="center"><b>Seção I</b></p>
        <p>§ 1º Não é título.</p>
        """);
    assertThat(r).extracting(DispositivoLegal::dispositivo)
        .containsExactly("art. 1º", "art. 2º", "art. 2º, § 1º");
  }

  @Test
  @DisplayName("Depois do rótulo, linha que termina em ponto é texto de lei e fica")
  void linhaComPontuacaoDepoisDoRotuloEhTexto() {
    List<DispositivoLegal> r = new FatiadorDispositivos().fatiar("""
        <p>Art. 1º Texto do primeiro artigo.</p>
        <p align="center">Seção I</p>
        <p>Continuação do artigo, que é texto de lei.</p>
        """);
    assertThat(r.get(0).texto()).endsWith("Continuação do artigo, que é texto de lei.");
  }

  @Test
  @DisplayName("Título sem rótulo reconhecível (centralizado, 'Do/Da...') sai; nota entre parênteses fica")
  void tituloSemRotuloSaiENotaFica() {
    List<DispositivoLegal> r = new FatiadorDispositivos().fatiar("""
        <p>Art. 1º Texto do primeiro artigo.</p>
        <p align="center"><b>DOS CONTRIBUINTES</b></p>
        <p>Art. 2º Texto do segundo artigo.</p>
        <p align="center">(Valores dados pela Lei nº 1, de 1995)</p>
        """);
    assertThat(r.get(0).texto()).isEqualTo("Art. 1º Texto do primeiro artigo.");
    assertThat(r.get(1).texto())
        .isEqualTo("Art. 2º Texto do segundo artigo. (Valores dados pela Lei nº 1, de 1995)");
  }

  @Test
  @DisplayName("Rótulo de anexo citado (LC 227) não é nome de seção: fica como estava")
  void rotuloDeAnexoCitadoFica() {
    List<DispositivoLegal> r = new FatiadorDispositivos().fatiar("""
        <p>Art. 1º Texto do primeiro artigo.</p>
        <p align="center">ANEXO VII</p>
        <p align="center">ALIMENTOS DESTINADOS AO CONSUMO HUMANO</p>
        <p>Brasília, 14 de janeiro de 2026; 205º da Independência.</p>
        """);
    assertThat(r.get(0).texto()).contains("ANEXO VII");
  }

  @Test
  @DisplayName("Link de anotação sem parênteses sai, também com o texto quebrado em duas linhas")
  void linkDeAnotacaoSai() {
    List<DispositivoLegal> r = new FatiadorDispositivos().fatiar("""
        <p>Art. 1º Texto.&nbsp; <a href="#art544-3.0">Produção
        de efeitos</a></p>
        <p>§ 1º Parágrafo. <a href="x.htm">Vigência</a></p>
        <p>§ 2º Outro. (<a href="x.htm">Sem eficácia</a>)</p>
        <p>§ 3º Mais um. <a href="x.htm">Vide Lei nº 15.371, de 2026</a></p>
        <p>§ 4º Ver a <a href="#art1">Produção de efeitos do art. 1º</a> e o <a href="#a">art. 1º</a>.</p>
        """);
    assertThat(r.get(0).texto()).isEqualTo("Art. 1º Texto.");
    assertThat(r.get(1).texto()).isEqualTo("Art. 1º Texto. § 1º Parágrafo.");
    assertThat(r.get(2).texto()).isEqualTo("Art. 1º Texto. § 2º Outro.");
    assertThat(r.get(3).texto()).isEqualTo("Art. 1º Texto. § 3º Mais um.");
    // o link com mais texto que a anotação é lei: fica
    assertThat(r.get(4).texto()).contains("Ver a Produção de efeitos do art. 1º e o art. 1º.");
  }
}
