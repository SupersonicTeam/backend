package br.com.agrocontabil.assistente.ferramentas;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.assistente.porta.TratamentoProdutoPorta.Alternativa;
import br.com.agrocontabil.assistente.porta.TratamentoProdutoPorta.Produto;
import br.com.agrocontabil.assistente.porta.TratamentoProdutoPorta.TratamentoConsultado;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TemplateTratamentoProdutoTest {

  private static final Produto SOJA = new Produto("12019000",
      "Soja, mesmo triturada, exceto para semeadura", 1, "200036", new BigDecimal("60.00"), true,
      null, "137", "vendido in natura, sem industrialização (LC 214/2025, art. 137)", true);
  private static final Produto ARROZ = new Produto("10063021", "Arroz polido", 1, "200003",
      new BigDecimal("100.00"), false, 1, "125", "destinados à alimentação humana", true);
  private static final Produto ADUBO = new Produto(null, "Fertilizantes (adubos)", 35, "200038",
      new BigDecimal("60.00"), false, 9, "138", null, true);
  private static final Produto INTEGRAL = new Produto("99999999", null, 1, "000001",
      BigDecimal.ZERO, false, null, null, null, true);

  @Test
  @DisplayName("in natura: percentual, artigo e cClassTrib do dado, e a condicao dita, nao afirmada")
  void inNatura() {
    String texto = TemplateTratamentoProduto.texto(
        new TratamentoConsultado("soja", List.of(SOJA), "IT 2025.002 v1.70"));

    assertThat(texto).isEqualTo("Soja, mesmo triturada, exceto para semeadura (NCM 1201.90.00):"
        + " redução de 60% do IBS e da CBS, como produto agropecuário in natura (LC 214/2025, art."
        + " 137; cClassTrib 200036). Condição: vendido in natura, sem industrialização (LC"
        + " 214/2025, art. 137).\nTratamento pela tabela oficial de classificação tributária (IT"
        + " 2025.002 v1.70). " + TemplateTratamentoProduto.RESSALVA);
  }

  @Test
  @DisplayName("leite (#284): as duas situacoes, as duas citacoes e o enquadramento com o contador")
  void leiteDuasSituacoes() {
    Produto leite = new Produto("04012090", "Leite com teor de gordura entre 1% e 6%, outros", 1,
        "200036", new BigDecimal("60.00"), true, null, "137",
        "leite cru ou apenas resfriado, vendido in natura (LC 214/2025, art. 137, § 1º, II)",
        true, new Alternativa("200003", new BigDecimal("100.00"), 1, "125",
            "leite para consumo direto pela população (Anexo I, item 2)"));
    TratamentoConsultado consulta =
        new TratamentoConsultado("leite", List.of(leite), "IT 2025.002 v1.70");

    assertThat(TemplateTratamentoProduto.texto(consulta)).isEqualTo("Leite com teor de gordura"
        + " entre 1% e 6%, outros (NCM 0401.20.90): redução de 60% do IBS e da CBS, como produto"
        + " agropecuário in natura (LC 214/2025, art. 137; cClassTrib 200036). Condição: leite cru"
        + " ou apenas resfriado, vendido in natura (LC 214/2025, art. 137, § 1º, II). Já o leite"
        + " para consumo direto pela população (Anexo I, item 2) tem alíquota zero do IBS e da CBS"
        + " (LC 214/2025, art. 125, Anexo I; cClassTrib 200003), com esse cClassTrib informado no"
        + " item da nota.\nTratamento pela tabela oficial de classificação tributária (IT 2025.002"
        + " v1.70). " + TemplateTratamentoProduto.RESSALVA_ALTERNATIVA);
    assertThat(TemplateTratamentoProduto.dispositivos(consulta))
        .containsExactly("LC 214/2025|art. 137", "LC 214/2025|art. 125");
  }

  @Test
  @DisplayName("leite junto com produto com condicao: as duas ressalvas, nenhuma some")
  void leiteEOutroProdutoComCondicao() {
    Produto leite = new Produto("04012090", "Leite", 1, "200036", new BigDecimal("60.00"), true,
        null, "137", "leite cru", true,
        new Alternativa("200003", new BigDecimal("100.00"), 1, "125", "leite para consumo direto"));

    assertThat(TemplateTratamentoProduto.texto(
            new TratamentoConsultado("x", List.of(leite, ARROZ), null)))
        .endsWith("Tratamento pela tabela oficial de classificação tributária. "
            + TemplateTratamentoProduto.RESSALVA + " "
            + TemplateTratamentoProduto.RESSALVA_ALTERNATIVA);
  }

  @Test
  @DisplayName("anexo de aliquota zero: 100% (aliquota zero), anexo em romano")
  void aliquotaZero() {
    assertThat(TemplateTratamentoProduto.frase(ARROZ)).isEqualTo("Arroz polido (NCM 1006.30.21):"
        + " redução de 100% do IBS e da CBS (alíquota zero), pelo Anexo I (LC 214/2025, art. 125,"
        + " Anexo I; cClassTrib 200003). Condição: destinados à alimentação humana.");
  }

  @Test
  @DisplayName("grupo de NCMs de um item de anexo e tributacao integral")
  void grupoEIntegral() {
    assertThat(TemplateTratamentoProduto.frase(ADUBO))
        .startsWith("Fertilizantes (adubos) (35 NCMs): redução de 60%")
        .contains("art. 138, Anexo IX");
    assertThat(TemplateTratamentoProduto.frase(INTEGRAL)).isEqualTo("NCM 9999.99.99: sem redução;"
        + " o IBS e a CBS são cobrados pela alíquota cheia (cClassTrib 000001).");
  }

  @Test
  @DisplayName("sem condicao, sem ressalva; dispositivos sem repetir; nada achado = frase fixa")
  void ressalvaDispositivosEVazio() {
    TratamentoConsultado dois = new TratamentoConsultado("x", List.of(ADUBO, ADUBO,
        INTEGRAL), null);
    assertThat(TemplateTratamentoProduto.texto(dois))
        .doesNotContain(TemplateTratamentoProduto.RESSALVA)
        .endsWith("Tratamento pela tabela oficial de classificação tributária.");
    assertThat(TemplateTratamentoProduto.dispositivos(dois))
        .containsExactly("LC 214/2025|art. 138");
    assertThat(TemplateTratamentoProduto.texto(
        new TratamentoConsultado("trator", List.of(), null)))
        .isEqualTo(TemplateTratamentoProduto.naoEncontrado("trator"))
        .doesNotContainPattern("\\d");
  }

  @Test
  @DisplayName("descricao longa e cortada na palavra; percentual em pt-BR")
  void formato() {
    String longa = "Palavra ".repeat(30);
    assertThat(TemplateTratamentoProduto.frase(new Produto("12019000", longa, 1, "200036",
        new BigDecimal("30.50"), true, null, "137", null, true)))
        .contains("... (NCM 1201.90.00)")
        .contains("redução de 30,5%");
  }

  @Test
  @DisplayName("review: NCM fora da referencia e dos anexos nao afirma a aliquota cheia")
  void ncmDesconhecido() {
    assertThat(TemplateTratamentoProduto.frase(new Produto("07133390", null, 1, "000001",
        BigDecimal.ZERO, false, null, null, null, false)))
        .startsWith("NCM 0713.33.90: não encontrei esse código")
        .contains("Se o código estiver certo")
        .endsWith("Confira o NCM na nota fiscal do produto.");
  }

  @Test
  @DisplayName("review: reducao sem artigo na descricao oficial nao imprime 'null'")
  void semArtigo() {
    assertThat(TemplateTratamentoProduto.frase(new Produto("12019000", "Soja", 1, "200038",
        new BigDecimal("60"), false, 9, null, null, true)))
        .contains("pelo Anexo IX (cClassTrib 200038).")
        .doesNotContain("null");
  }
}
