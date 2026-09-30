package br.com.agrocontabil.assistente.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fim do texto articulado e anexos (issue #156), no formato do compilado da LC 214/2025.
 *
 * <p>Motivo: o último artigo engolia a assinatura, a nota do DOU e os 16 anexos inteiros — um
 * trecho de 91.820 caracteres, que estourava o limite de tokens do embedding e tornava o Anexo IX
 * (insumos agropecuários) impossível de citar item a item.
 */
class FatiadorAnexosTest {

  private static final String HTML = """
      <html><body>
      <p>Art. 544. Esta Lei Complementar entra em vigor na data de sua publicação.</p>
      <p>Brasília, 16 de janeiro de 2025; 204o da Independência e 137o da República.</p>
      <p>LUIZ INÁCIO LULA DA SILVA</p>
      <p>Este texto não substitui o publicado no DOU de 16.1.2025</p>
      <p>ANEXO IX</p>
      <p>INSUMOS AGROPECUÁRIOS E AQUÍCOLAS SUBMETIDOS À REDUÇÃO</p>
      <p>DE 60% (SESSENTA POR CENTO) DAS ALÍQUOTAS DO IBS E DA CBS</p>
      <table>
        <tr><td><p>ITEM</p></td><td><p>DESCRIÇÃO</p></td><td><p>NBS / NCM/SH</p></td></tr>
        <tr><td><p>1</p></td><td><p>Biofertilizantes</p></td><td><p>3101.00.00</p></td></tr>
        <tr><td><p>2</p></td><td><p>Corretivo de solo</p></td><td><p>2521.00.00</p></td></tr>
        <tr><td><p></p></td><td><p>calcário agrícola</p></td><td><p></p></td></tr>
      </table>
      <p>ANEXO X</p>
      <p>PRODUTOS HORTÍCOLAS</p>
      <table>
        <tr><td><p>1</p></td><td><p>Batata</p></td></tr>
      </table>
      </body></html>
      """;

  private final List<DispositivoLegal> dispositivos = new FatiadorDispositivos().fatiar(HTML);

  @Test
  @DisplayName("O ultimo artigo termina na assinatura: sem assinaturas, nota do DOU nem anexos")
  void artigoTerminaNaAssinatura() {
    assertThat(dispositivos.get(0)).isEqualTo(new DispositivoLegal("art. 544",
        "Art. 544. Esta Lei Complementar entra em vigor na data de sua publicação."));
  }

  @Test
  @DisplayName("Cada item numerado do anexo vira um trecho, com o titulo do anexo")
  void itensDeAnexo() {
    assertThat(dispositivos).extracting(DispositivoLegal::dispositivo).containsExactly(
        "art. 544", "Anexo IX, item 1", "Anexo IX, item 2", "Anexo X, item 1");
    assertThat(dispositivos.get(1).texto()).isEqualTo("ANEXO IX - INSUMOS AGROPECUÁRIOS E "
        + "AQUÍCOLAS SUBMETIDOS À REDUÇÃO DE 60% (SESSENTA POR CENTO) DAS ALÍQUOTAS DO IBS E DA "
        + "CBS. Item 1: Biofertilizantes - 3101.00.00");
  }

  @Test
  @DisplayName("Linha sem numero continua o item anterior; o cabecalho da tabela e descartado")
  void continuacaoECabecalho() {
    assertThat(dispositivos.get(2).texto())
        .endsWith("Item 2: Corretivo de solo - 2521.00.00 calcário agrícola")
        .doesNotContain("DESCRIÇÃO");
    assertThat(dispositivos.get(3).texto()).isEqualTo("ANEXO X - PRODUTOS HORTÍCOLAS. Item 1: Batata");
  }
}
