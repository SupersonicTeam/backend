package br.com.agrocontabil.assistente.orquestracao;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.assistente.ValidadorNumeros;
import br.com.agrocontabil.assistente.porta.TratamentoProdutoPorta.Produto;
import br.com.agrocontabil.model.TipoProdutor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Texto explicado do atalho de legislação (#347): correto por construção. O texto da lei vem do
 * fixture {@code ceres/atalho-reforma-trechos.json}, copiado da base de legislação (a mesma da
 * VPS) em 2026-11-04 — se um dispositivo mudar de redação, o fixture e o texto mudam juntos. A
 * exceção, até a conferência com a base da VPS, é o art. 138, § 2º, inciso I, alínea a, copiado
 * do normas.leg.br (a chave {@code _fonte} do fixture diz isso).
 */
class TextoReformaTributariaTest {

  private static final Produto SOJA = new Produto("12019000", "Soja, mesmo triturada", 1,
      "515001", new BigDecimal("60.00"), true, null, "137", null, true);
  private static final Produto ALFACE = new Produto("07051900", "Alfaces", 1, "200003",
      new BigDecimal("100.00"), false, 15, "148", null, true);

  private static Map<String, String> lei;

  @BeforeAll
  static void carregarFixture() throws IOException {
    try (InputStream in = TextoReformaTributariaTest.class
        .getResourceAsStream("/ceres/atalho-reforma-trechos.json")) {
      lei = new ObjectMapper().readValue(in, new TypeReference<Map<String, String>>() { });
    }
  }

  @Test
  @DisplayName("Todo dispositivo citado (fixo e do Funrural) esta no fixture da base")
  void dispositivosNoFixture() {
    List<String> todos = new ArrayList<>(TextoReformaTributaria.DISPOSITIVOS_FIXOS);
    todos.add(TextoReformaTributaria.DISPOSITIVO_FUNRURAL);
    assertThat(lei).containsKeys(todos.toArray(String[]::new));
  }

  @ParameterizedTest
  @EnumSource(TipoProdutor.class)
  @DisplayName("Todo numero do texto, fora as referencias entre parenteses, esta escrito nos "
      + "dispositivos que ele cita")
  void todoNumeroTemFonteNaLei(TipoProdutor tipo) {
    TextoReformaTributaria.Texto texto =
        TextoReformaTributaria.montar(new TextoReformaTributaria.Perfil(tipo, true, List.of()));
    List<String> fontes = texto.dispositivos().stream().map(lei::get).toList();

    String semReferencias = texto.texto().replaceAll("\\([^)]*\\)", "");
    assertThat(new ValidadorNumeros().verificar(semReferencias, fontes).rastreavel())
        .as(semReferencias).isTrue();
  }

  @Test
  @DisplayName("Cada numero esta no dispositivo da sua afirmacao, escrito igual")
  void numeroNoDispositivoCerto() {
    String texto = TextoReformaTributaria.montar(TextoReformaTributaria.Perfil.DESCONHECIDO)
        .texto();
    Map<String, String> numeroPorDispositivo = Map.ofEntries(
        Map.entry("0,9%", "LC 214/2025|art. 346"),
        Map.entry("0,1%", "LC 214/2025|art. 343"),
        Map.entry("0,05%", "LC 214/2025|art. 344"),
        Map.entry("0,1 ", "LC 214/2025|art. 347"),
        Map.entry("9/10", "EC 132/2023|art. 2º (redação do art. 128, inciso I)"),
        Map.entry("8/10", "EC 132/2023|art. 2º (redação do art. 128, inciso II)"),
        Map.entry("7/10", "EC 132/2023|art. 2º (redação do art. 128, inciso III)"),
        Map.entry("6/10", "EC 132/2023|art. 2º (redação do art. 128, inciso IV)"),
        Map.entry("2033", "EC 132/2023|art. 2º (redação do art. 129)"),
        Map.entry("2027", "EC 132/2023|art. 2º (redação do art. 126, inciso I, alínea a)"),
        Map.entry("60%", "LC 214/2025|art. 137"),
        Map.entry("R$ 3.600.000,00", "LC 214/2025|art. 164"));
    numeroPorDispositivo.forEach((numero, dispositivo) -> {
      assertThat(texto).as("texto").contains(numero);
      assertThat(lei.get(dispositivo)).as(dispositivo).contains(numero);
    });
    // A reducao dos insumos e a do Anexo IX, nao a do in natura.
    assertThat(lei.get("LC 214/2025|art. 138")).contains("60%").contains("Anexo IX");
  }

  @Test
  @DisplayName("Insumo do Anexo IX: diferido entre contribuintes do regime regular, sem credito "
      + "a recuperar, e o credito do art. 47 so fora do diferimento")
  void insumoDoAnexoIxDiferido() {
    String texto = TextoReformaTributaria.COMPRAS;
    String alinea = lei.get("LC 214/2025|art. 138, § 2º, inciso I, alínea a");
    assertThat(alinea).contains("Fica diferido o recolhimento do IBS e da CBS")
        .contains("fornecimento realizado por contribuinte sujeito ao regime regular")
        .endsWith("a) contribuinte sujeito ao regime regular do IBS e da CBS; e");
    // O art. 47 so da credito com o debito extinto: recolhimento diferido nao e imposto pago.
    assertThat(lei.get("LC 214/2025|art. 47")).contains("quando ocorrer a extinção");
    assertThat(texto).contains("fica diferido (art. 138, § 2º, inciso I, alinea a)")
        .contains("(art. 47). No insumo com o recolhimento diferido nao ha imposto pago na compra"
            + " para virar credito");
    assertThat(TextoReformaTributaria.DA_NOTA_AO_PAGAMENTO).containsSubsequence(
        "LC 214/2025|art. 138", "LC 214/2025|art. 138, § 2º, inciso I, alínea a",
        "LC 214/2025|art. 47");
  }

  @ParameterizedTest
  @EnumSource(TipoProdutor.class)
  @DisplayName("Todo artigo nomeado no texto esta nas citacoes, e vice-versa, com a norma e na "
      + "mesma ordem")
  void artigosCoerentesComAsCitacoes(TipoProdutor tipo) {
    TextoReformaTributaria.Texto texto =
        TextoReformaTributaria.montar(new TextoReformaTributaria.Perfil(tipo, false, List.of()));
    assertThat(artigosNomeados(texto.texto())).isEqualTo(artigosCitados(texto.dispositivos()));
  }

  @Test
  @DisplayName("Produtos mais vendidos: frase da tabela oficial, artigo nas citacoes e a ressalva "
      + "do contador")
  void produtosMaisVendidos() {
    TextoReformaTributaria.Texto texto = TextoReformaTributaria.montar(
        new TextoReformaTributaria.Perfil(TipoProdutor.PESSOA_JURIDICA, true,
            List.of(SOJA, ALFACE)));

    assertThat(texto.texto())
        .contains(TextoReformaTributaria.VENDA_PRODUTOS)
        .contains("\n- Soja, mesmo triturada (NCM 1201.90.00): reducao de 60% do IBS e da CBS, como"
            + " produto agropecuario in natura (LC 214/2025, art. 137; cClassTrib 515001).")
        .contains("Alfaces (NCM 0705.19.00): reducao de 100% do IBS e da CBS (aliquota zero)")
        .contains(TextoReformaTributaria.VENDA_ENQUADRAMENTO)
        .doesNotContain(TextoReformaTributaria.VENDA_SEM_PRODUTOS);
    assertThat(texto.dispositivos()).contains("LC 214/2025|art. 137", "LC 214/2025|art. 148")
        .doesNotHaveDuplicates();
    assertThat(artigosNomeados(texto.texto())).isEqualTo(artigosCitados(texto.dispositivos()));
  }

  @Test
  @DisplayName("Artigo novo de um produto entra nas citacoes no lugar dele no texto: depois dos da "
      + "venda e antes da nota fiscal")
  void artigoDoProdutoNaOrdem() {
    Produto mel = new Produto("04090000", "Mel natural", 1, "200099", new BigDecimal("60.00"),
        false, 7, "144", null, true);
    TextoReformaTributaria.Texto texto = TextoReformaTributaria.montar(
        new TextoReformaTributaria.Perfil(null, null, List.of(mel)));

    List<String> d = texto.dispositivos();
    assertThat(d.indexOf("LC 214/2025|art. 144"))
        .isEqualTo(d.indexOf("LC 214/2025|art. 148") + 1)
        .isEqualTo(d.indexOf("LC 214/2025|art. 60, § 1º") - 1);
    assertThat(artigosNomeados(texto.texto())).isEqualTo(artigosCitados(d));
  }

  @Test
  @DisplayName("Sem produto vendido: como perguntar pelo produto, sem a ressalva da lista")
  void semProdutos() {
    String texto = TextoReformaTributaria.montar(TextoReformaTributaria.Perfil.DESCONHECIDO)
        .texto();
    assertThat(texto).contains(TextoReformaTributaria.VENDA_SEM_PRODUTOS)
        .doesNotContain(TextoReformaTributaria.VENDA_PRODUTOS)
        .doesNotContain(TextoReformaTributaria.VENDA_ENQUADRAMENTO);
  }

  @Test
  @DisplayName("Pessoa fisica: Funrural retido pelo comprador, com a Lei 8.212 citada")
  void pessoaFisicaComFunrural() {
    TextoReformaTributaria.Texto texto = TextoReformaTributaria.montar(
        new TextoReformaTributaria.Perfil(TipoProdutor.PESSOA_FISICA, null, List.of()));
    assertThat(texto.texto()).contains(TextoReformaTributaria.PAGAMENTO_PESSOA_FISICA)
        .doesNotContain(TextoReformaTributaria.PAGAMENTO_OUTROS);
    // Na ordem do texto: depois do split payment (NO PAGAMENTO) e antes de QUEM PAGA.
    List<String> d = texto.dispositivos();
    assertThat(d.indexOf(TextoReformaTributaria.DISPOSITIVO_FUNRURAL))
        .isEqualTo(d.indexOf("LC 214/2025|art. 32") + 1)
        .isEqualTo(d.indexOf("LC 214/2025|art. 164") - 1);
  }

  @Test
  @DisplayName("Pessoa juridica ou tipo desconhecido: Funrural e ICMS com o contador, sem a "
      + "retencao do produtor pessoa fisica")
  void outrosSemRetencao() {
    for (TipoProdutor tipo : new TipoProdutor[] {TipoProdutor.PESSOA_JURIDICA, null}) {
      TextoReformaTributaria.Texto texto = TextoReformaTributaria.montar(
          new TextoReformaTributaria.Perfil(tipo, null, List.of()));
      assertThat(texto.texto()).contains(TextoReformaTributaria.PAGAMENTO_OUTROS)
          .doesNotContain("Lei 8.212");
      assertThat(texto.dispositivos()).doesNotContain(TextoReformaTributaria.DISPOSITIVO_FUNRURAL);
    }
  }

  @Test
  @DisplayName("Proximo passo pelo escritorio: com, sem e desconhecido")
  void proximoPasso() {
    assertThat(texto(true)).contains(TextoReformaTributaria.PROXIMO_COM_ESCRITORIO);
    assertThat(texto(false)).contains(TextoReformaTributaria.PROXIMO_SEM_ESCRITORIO);
    assertThat(texto(null)).contains(TextoReformaTributaria.PROXIMO_GENERICO);
  }

  @ParameterizedTest
  @EnumSource(TipoProdutor.class)
  @DisplayName("Premissas da #280: nunca afirma a situacao do produtor no IBS/CBS nem traz o que "
      + "esta fora do escopo")
  void premissasDoEscopo(TipoProdutor tipo) {
    String texto = normalizado(TextoReformaTributaria.montar(
        new TextoReformaTributaria.Perfil(tipo, true, List.of(SOJA))).texto());
    assertThat(texto)
        .doesNotContain("voce e optante").doesNotContain("voce nao e contribuinte")
        .doesNotContain("voce e contribuinte").doesNotContain("simples nacional")
        .doesNotContainPattern("\\bmei\\b").doesNotContain("lc 123").doesNotContain("diesel")
        .doesNotContain("1,63").doesNotContain("1,5%").doesNotContain("exportacao")
        // Insumo do Anexo IX nao gera credito (diferido, art. 138, § 2º): o texto diz isso, e o
        // sistema nao modela o diferimento (premissas, secao 1).
        .doesNotContain("insumos geram credito").doesNotContain("credito dos insumos")
        // Split payment e o que a lei preve; o sistema nao calcula.
        .contains("a lei preve o split payment")
        .contains("confirme com o contador se esse e o seu caso");
    assertThat(TextoReformaTributaria.montar(new TextoReformaTributaria.Perfil(tipo, true,
        List.of())).texto()).doesNotContainPattern("[áàâãéêíóôõúç]");
  }

  @Test
  @DisplayName("Formato da tela: texto puro, secoes separadas por linha em branco, sem HTML")
  void formatoDaTela() {
    String texto = TextoReformaTributaria.montar(TextoReformaTributaria.Perfil.DESCONHECIDO)
        .texto();
    assertThat(texto).startsWith(TextoReformaTributaria.TITULO + "\n\nO QUE SAO\n- ")
        .contains("\n\nO CALENDARIO\n- ").contains("\n\nNA SUA VENDA\n- ")
        .contains("\n\nNAS SUAS COMPRAS\n- ").contains("\n\nNO PAGAMENTO\n- ")
        .contains("\n\nQUEM PAGA\n- ").contains("\n\nPROXIMO PASSO\n- ")
        .doesNotContain("<").doesNotContain("**");
  }

  private static String texto(Boolean atendido) {
    return TextoReformaTributaria.montar(
        new TextoReformaTributaria.Perfil(null, atendido, List.of())).texto();
  }

  /**
   * Artigos nomeados no texto, com a norma ({@code LC 214:125}, {@code ADCT:125},
   * {@code Lei 8.212:30}), na ordem da primeira vez: o art. 125 da LC 214 (cesta básica) e o do
   * ADCT (2026) não se confundem.
   */
  private static List<String> artigosNomeados(String texto) {
    Matcher artigos = Pattern.compile("\\barts?\\. (\\d+)º?(?: e (\\d+))?( do ADCT)?")
        .matcher(texto);
    Set<String> nomeados = new LinkedHashSet<>();
    while (artigos.find()) {
      String antes = texto.substring(Math.max(0, artigos.start() - 20), artigos.start());
      String norma = artigos.group(3) != null ? "ADCT"
          : antes.contains("8.212") ? "Lei 8.212" : "LC 214";
      nomeados.add(norma + ":" + artigos.group(1));
      if (artigos.group(2) != null) {
        nomeados.add(norma + ":" + artigos.group(2));
      }
    }
    return List.copyOf(nomeados);
  }

  /** Artigos citados, com a norma como em {@link #artigosNomeados}, na ordem das citações. */
  private static List<String> artigosCitados(List<String> dispositivos) {
    Pattern redacao = Pattern.compile("\\(redação do art\\. (\\d+)");
    Pattern artigo = Pattern.compile("^art\\. (\\d+)");
    Set<String> citados = new LinkedHashSet<>();
    for (String dispositivo : dispositivos) {
      String norma = dispositivo.substring(0, dispositivo.indexOf('|'));
      String rotulo = dispositivo.substring(dispositivo.indexOf('|') + 1);
      Matcher m = redacao.matcher(rotulo);
      if (m.find()) {
        assertThat(norma).as(dispositivo).isEqualTo("EC 132/2023");
        citados.add("ADCT:" + m.group(1));
        continue;
      }
      m = artigo.matcher(rotulo);
      assertThat(m.find()).as(rotulo).isTrue();
      String chave = switch (norma) {
        case "LC 214/2025" -> "LC 214";
        case "Lei 8.212/1991" -> "Lei 8.212";
        default -> throw new AssertionError("norma inesperada: " + dispositivo);
      };
      citados.add(chave + ":" + m.group(1));
    }
    return List.copyOf(citados);
  }

  private static String normalizado(String texto) {
    return Normalizer.normalize(texto, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
        .toLowerCase(Locale.ROOT);
  }
}
