package br.com.agrocontabil.assistente.ferramentas;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.assistente.porta.TratamentoProdutoPorta.Produto;
import br.com.agrocontabil.assistente.porta.TratamentoProdutoPorta.TratamentoConsultado;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TemplateTratamentoProdutoTest {

  private static final Produto SOJA = new Produto("12019000",
      "Soja, mesmo triturada, exceto para semeadura", 1, "200036", new BigDecimal("60.00"), true,
      null, "137", "vendido in natura, sem industrializacao (LC 214/2025, art. 137)");
  private static final Produto ARROZ = new Produto("10063021", "Arroz polido", 1, "200003",
      new BigDecimal("100.00"), false, 1, "125", "destinados à alimentação humana");
  private static final Produto ADUBO = new Produto(null, "Fertilizantes (adubos)", 35, "200038",
      new BigDecimal("60.00"), false, 9, "138", null);
  private static final Produto INTEGRAL = new Produto("99999999", null, 1, "000001",
      BigDecimal.ZERO, false, null, null, null);

  @Test
  @DisplayName("in natura: percentual, artigo e cClassTrib do dado, e a condicao dita, nao afirmada")
  void inNatura() {
    String texto = TemplateTratamentoProduto.texto(
        new TratamentoConsultado("soja", false, List.of(SOJA), "IT 2025.002 v1.70"));

    assertThat(texto).isEqualTo("Soja, mesmo triturada, exceto para semeadura (NCM 1201.90.00):"
        + " reducao de 60% do IBS e da CBS, como produto agropecuario in natura (LC 214/2025, art."
        + " 137; cClassTrib 200036). Condicao: vendido in natura, sem industrializacao (LC"
        + " 214/2025, art. 137).\nTratamento pela tabela oficial de classificacao tributaria (IT"
        + " 2025.002 v1.70). " + TemplateTratamentoProduto.RESSALVA);
  }

  @Test
  @DisplayName("anexo de aliquota zero: 100% (aliquota zero), anexo em romano")
  void aliquotaZero() {
    assertThat(TemplateTratamentoProduto.frase(ARROZ)).isEqualTo("Arroz polido (NCM 1006.30.21):"
        + " reducao de 100% do IBS e da CBS (aliquota zero), pelo Anexo I (LC 214/2025, art. 125,"
        + " Anexo I; cClassTrib 200003). Condicao: destinados à alimentação humana.");
  }

  @Test
  @DisplayName("grupo de NCMs de um item de anexo e tributacao integral")
  void grupoEIntegral() {
    assertThat(TemplateTratamentoProduto.frase(ADUBO))
        .startsWith("Fertilizantes (adubos) (35 NCMs): reducao de 60%")
        .contains("art. 138, Anexo IX");
    assertThat(TemplateTratamentoProduto.frase(INTEGRAL)).isEqualTo("NCM 9999.99.99: sem reducao;"
        + " o IBS e a CBS sao cobrados pela aliquota cheia (cClassTrib 000001).");
  }

  @Test
  @DisplayName("sem condicao, sem ressalva; dispositivos sem repetir; nada achado = frase fixa")
  void ressalvaDispositivosEVazio() {
    TratamentoConsultado dois = new TratamentoConsultado("x", false, List.of(ADUBO, ADUBO,
        INTEGRAL), null);
    assertThat(TemplateTratamentoProduto.texto(dois))
        .doesNotContain(TemplateTratamentoProduto.RESSALVA)
        .endsWith("Tratamento pela tabela oficial de classificacao tributaria.");
    assertThat(TemplateTratamentoProduto.dispositivos(dois))
        .containsExactly("LC 214/2025|art. 138");
    assertThat(TemplateTratamentoProduto.texto(
        new TratamentoConsultado("trator", false, List.of(), null)))
        .isEqualTo(TemplateTratamentoProduto.naoEncontrado("trator"))
        .doesNotContainPattern("\\d");
  }

  @Test
  @DisplayName("descricao longa e cortada na palavra; percentual em pt-BR")
  void formato() {
    String longa = "Palavra ".repeat(30);
    assertThat(TemplateTratamentoProduto.frase(new Produto("12019000", longa, 1, "200036",
        new BigDecimal("30.50"), true, null, "137", null)))
        .contains("... (NCM 1201.90.00)")
        .contains("reducao de 30,5%");
  }
}
