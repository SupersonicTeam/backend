package br.com.agrocontabil.nfe;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("TributacaoIcms — CST do grupo ICMS: informado, 51 padrao ou a definir")
class TributacaoIcmsTest {

  @Test
  @DisplayName("venda interna de producao propria a contribuinte: 51 (diferimento), sem informar")
  void padraoDiferimento() {
    assertThat(TributacaoIcms.cst(null, "5101", "MT", "MT", "133518276")).contains("51");
    assertThat(TributacaoIcms.cst(" ", "5101", "mt", " MT ", "133518276")).contains("51");
  }

  @ParameterizedTest(name = "CFOP {0} {1}->{2}, IE [{3}]: a definir")
  @CsvSource({
      // interestadual, exportacao, revenda, remessa, destinatario sem IE
      "6101, MT, PR, 9071425318",
      "7101, MT, EX, ''",
      "5102, MT, MT, 133518276",
      "5949, MT, MT, 133518276",
      "5101, MT, MT, ''",
      "5101, MT, MT, ' '",
      // 5101 com UFs diferentes e invalido no ambito, mas nunca ganha o padrao
      "5101, MT, PR, 9071425318",
      "5101, MT, EX, 9071425318"
  })
  void foraDoPadraoFicaADefinir(String cfop, String ufEmitente, String ufDestinatario,
      String ie) {
    assertThat(TributacaoIcms.cst(null, cfop, ufEmitente, ufDestinatario, ie)).isEmpty();
  }

  @Test
  @DisplayName("sem UF ou sem CFOP nao ha padrao")
  void semDadosNaoHaPadrao() {
    assertThat(TributacaoIcms.cst(null, null, "MT", "MT", "1")).isEmpty();
    assertThat(TributacaoIcms.cst(null, "5101", null, "MT", "1")).isEmpty();
    assertThat(TributacaoIcms.cst(null, "5101", "MT", null, "1")).isEmpty();
    assertThat(TributacaoIcms.cst(null, "5101", "MT", "MT", null)).isEmpty();
  }

  @Test
  @DisplayName("CST informado e respeitado, inclusive no caso do padrao")
  void informadoPrevalece() {
    assertThat(TributacaoIcms.cst("41", "6101", "MT", "PR", "1")).contains("41");
    assertThat(TributacaoIcms.cst("90", "5101", "MT", "MT", "133518276")).contains("90");
  }

  @Test
  @DisplayName("lista de CSTs sem calculo e o texto da mensagem")
  void listaSemCalculo() {
    assertThat(TributacaoIcms.CSTS_SEM_CALCULO).containsExactly("40", "41", "50", "51", "90");
    assertThat(TributacaoIcms.CSTS_SEM_CALCULO_TEXTO).isEqualTo("40, 41, 50, 51 ou 90");
  }

  @Test
  @DisplayName("DANFE imprime origem + CST (MOC Anexo II)")
  void impresso() {
    assertThat(TributacaoIcms.impresso("0", "51")).isEqualTo("051");
    assertThat(TributacaoIcms.impresso("0", "102")).isEqualTo("0102");
    assertThat(TributacaoIcms.impresso("0", null)).isNull();
  }
}
