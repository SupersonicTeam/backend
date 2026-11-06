package br.com.agrocontabil.nfe;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.model.RegimeTributario;
import br.com.agrocontabil.model.TipoProdutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("TributacaoIcms — CST do grupo ICMS: informado, 51 padrao ou a definir")
class TributacaoIcmsTest {

  private static TributacaoIcms.DadosOperacao regular(String ufEmitente, String ufDestinatario,
      String ie) {
    return new TributacaoIcms.DadosOperacao(TipoProdutor.PESSOA_FISICA, RegimeTributario.REAL,
        ufEmitente, ufDestinatario, ie);
  }

  @ParameterizedTest(name = "{0}/{1}: CRT {2}")
  @CsvSource({"PESSOA_JURIDICA, SIMPLES, 1", "PESSOA_JURIDICA, MEI, 4"})
  @DisplayName("emitente do Simples ou MEI: sem padrao nem CST (CSOSN fora do escopo)")
  void emitenteSimples(TipoProdutor tipo, RegimeTributario regime, String crt) {
    TributacaoIcms.DadosOperacao dados =
        new TributacaoIcms.DadosOperacao(tipo, regime, "MT", "MT", "133518276");
    assertThat(TributacaoIcms.cst(null, "5101", dados)).isEmpty();
    assertThat(TributacaoIcms.cst("41", "5101", dados)).isEmpty();
    assertThat(TributacaoIcms.problema("41", "5101", dados))
        .contains(TributacaoIcms.Problema.EMITENTE_SIMPLES);
    // Pessoa fisica e sempre CRT 3 (#298), mesmo com SIMPLES gravado.
    assertThat(TributacaoIcms.problema(null, "5101", new TributacaoIcms.DadosOperacao(
        TipoProdutor.PESSOA_FISICA, regime, "MT", "MT", "133518276"))).isEmpty();
  }

  @ParameterizedTest(name = "CST {0}, CFOP {1}: {2}")
  @CsvSource({
      "'', 5101, ",
      "'', 6101, A_DEFINIR",
      "'', 7101, EXPORTACAO_A_DEFINIR",
      "41, 7101, ",
      "51, 7101, EXPORTACAO_SEM_41",
      "00, 6101, FORA_DA_LISTA",
      "90, 6101, "
  })
  @DisplayName("problema: a mesma regra do validador e do sinal")
  void problema(String cst, String cfop, TributacaoIcms.Problema esperado) {
    String ufDestino = cfop.startsWith("7") ? "EX" : cfop.startsWith("6") ? "PR" : "MT";
    assertThat(TributacaoIcms.problema(cst, cfop, regular("MT", ufDestino, "133518276")))
        .isEqualTo(java.util.Optional.ofNullable(esperado));
  }

  @Test
  @DisplayName("a regex do DTO e a lista de CSTs sao a mesma coisa (mais o vazio)")
  void regexDoDtoCasaComALista() {
    assertThat(TributacaoIcms.CSTS_SEM_CALCULO_REGEX)
        .isEqualTo("|" + String.join("|", TributacaoIcms.CSTS_SEM_CALCULO));
  }

  @Test
  @DisplayName("venda interna de producao propria a contribuinte: 51 (diferimento), sem informar")
  void padraoDiferimento() {
    assertThat(TributacaoIcms.cst(null, "5101", regular("MT", "MT", "133518276"))).contains("51");
    assertThat(TributacaoIcms.cst(" ", "5101", regular("mt", " MT ", "133518276"))).contains("51");
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
    assertThat(TributacaoIcms.cst(null, cfop, regular(ufEmitente, ufDestinatario, ie))).isEmpty();
  }

  @Test
  @DisplayName("sem UF ou sem CFOP nao ha padrao")
  void semDadosNaoHaPadrao() {
    assertThat(TributacaoIcms.cst(null, null, regular("MT", "MT", "1"))).isEmpty();
    assertThat(TributacaoIcms.cst(null, "5101", regular(null, "MT", "1"))).isEmpty();
    assertThat(TributacaoIcms.cst(null, "5101", regular("MT", null, "1"))).isEmpty();
    assertThat(TributacaoIcms.cst(null, "5101", regular("MT", "MT", null))).isEmpty();
  }

  @Test
  @DisplayName("CST informado e respeitado, inclusive no caso do padrao")
  void informadoPrevalece() {
    assertThat(TributacaoIcms.cst("41", "6101", regular("MT", "PR", "1"))).contains("41");
    assertThat(TributacaoIcms.cst("90", "5101", regular("MT", "MT", "133518276"))).contains("90");
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
