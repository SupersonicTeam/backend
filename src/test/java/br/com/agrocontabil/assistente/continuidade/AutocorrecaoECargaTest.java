package br.com.agrocontabil.assistente.continuidade;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.assistente.orquestracao.Intencao;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Descrição D da autocorreção, leitura estrita da carga e cortes da emissão (#499). */
class AutocorrecaoECargaTest {

  // --- D ---

  @Test
  @DisplayName("D: um rótulo por função, com datas, mês e situação")
  void rotulos() {
    assertThat(d("totalVendasPorProduto", "{\"descricaoOuNcm\":\"soja\",\"dataInicio\":"
        + "\"2026-01-01\",\"dataFim\":\"2026-06-30\"}"))
        .isEqualTo("vendas de soja de 01/01/2026 a 30/06/2026");
    assertThat(d("totalComprasPorInsumo", "{\"descricaoOuNcm\":\"adubo\"}"))
        .isEqualTo("compras de adubo");
    assertThat(d("listarNotas", "{}")).isEqualTo("suas notas mais recentes");
    assertThat(d("listarNotas", "{\"tipo\":\"SAIDA\",\"situacao\":\"DEVOLVIDA\"}"))
        .isEqualTo("suas notas de venda com a situação devolvida pelo contador");
    assertThat(d("listarNotas", "{\"tipo\":\"ENTRADA\"}")).isEqualTo("suas notas de compra");
    assertThat(d("consultarNota", "{\"numero\":15}")).isEqualTo("a nota 15");
    assertThat(d("consultarApuracao", "{\"mes\":\"2026-10\"}"))
        .isEqualTo("a apuração de 10/2026");
    assertThat(d("consultarApuracao", "{}")).isEqualTo("a apuração do mês atual");
    assertThat(d("vencimentosDoMes", "{}")).isEqualTo("os vencimentos do mês atual");
    assertThat(d("vencimentosDoMes", "{\"mes\":\"xx\"}")).isEqualTo("os vencimentos de xx");
    assertThat(d("proximosVencimentos", "{\"dias\":15}"))
        .isEqualTo("os vencimentos dos próximos 15 dias");
    assertThat(d("proximosVencimentos", "{}")).isEqualTo("os vencimentos dos próximos 30 dias");
    assertThat(d("consultarTratamentoProduto", "{\"produtoOuNcm\":\"milho\"}"))
        .isEqualTo("o tratamento de milho na reforma");
    assertThat(d("outra", "{}")).isEqualTo("seus dados");
    assertThat(d("totalVendasPorProduto", "{\"dataInicio\":\"x\",\"dataFim\":\"y\"}"))
        .isEqualTo("vendas de ?");
  }

  @Test
  @DisplayName("D: rótulos juntados por vírgula e 'e', sem repetir; vazio = 'os seus dados'")
  void juntar() {
    ChamadaAssinada a = new ChamadaAssinada("guiasEmAtraso", "{}");
    ChamadaAssinada b = new ChamadaAssinada("listarAlertas", "{}");
    ChamadaAssinada c = new ChamadaAssinada("resumoDoProdutor", "{}");
    assertThat(Autocorrecao.descrever(List.of(a, b, c, a))).isEqualTo(
        "as guias em atraso, os prazos dos próximos dias e o resumo das suas pendências");
    assertThat(Autocorrecao.descrever(List.of())).isEqualTo("os seus dados");
  }

  // --- carga estrita ---

  @ParameterizedTest(name = "[{index}] {0}")
  @ValueSource(strings = {
      "[]",
      "{\"rota\":\"DADOS\",\"reescrita\":false}",
      "{\"exp\":\"1\",\"rota\":\"DADOS\",\"reescrita\":false}",
      "{\"exp\":1,\"rota\":\"DADOS\",\"reescrita\":\"nao\"}",
      "{\"exp\":1,\"rota\":\"DADOS\",\"reescrita\":false,\"consulta\":[]}",
      "{\"exp\":1,\"rota\":\"DADOS\",\"reescrita\":false,\"consulta\":[{\"f\":\"x\"}]}",
      "{\"exp\":1,\"rota\":\"DADOS\",\"reescrita\":false,\"consulta\":[{\"f\":\"x 1\",\"a\":{}}]}",
      "{\"exp\":1,\"rota\":\"DADOS\",\"reescrita\":false,\"consulta\":[{\"f\":\"x\",\"a\":1}]}",
      "{\"exp\":1,\"rota\":\"DADOS\",\"reescrita\":false,\"dados\":\"curto\"}",
      "{\"exp\":1,\"rota\":\"DADOS\",\"reescrita\":false,\"t\":\"x\"}",
      "{\"exp\":1,\"rota\":\"DADOS\",\"reescrita\":false,\"t\":[[5,2]]}",
      "{\"exp\":1,\"rota\":\"DADOS\",\"reescrita\":false,\"t\":[[1]]}",
      "{\"exp\":1,\"rota\":\"MISTO\",\"reescrita\":false,\"lei\":{}}",
      "{\"exp\":1,\"rota\":\"MISTO\",\"reescrita\":false,\"lei\":{\"d\":\"OUTRA\"}}",
      "{\"exp\":1,\"rota\":\"MISTO\",\"reescrita\":false,\"lei\":{\"d\":\"CONFIRMADA\",\"x\":1}}",
      "{\"exp\":1,\"rota\":\"MISTO\",\"reescrita\":false,\"lei\":{\"d\":\"CONFIRMADA\","
          + "\"c\":[\"semBarra\"]}}",
      "{\"exp\":1,\"rota\":\"MISTO\",\"reescrita\":false,\"lei\":{\"d\":\"CONFIRMADA\","
          + "\"q\":\"\"}}",
      "{\"exp\":1,\"rota\":\"MISTO\",\"reescrita\":false,\"lei\":{\"d\":\"CONFIRMADA\","
          + "\"s\":false}}",
      "{\"exp\":1,\"rota\":\"MISTO\",\"reescrita\":false,\"lei\":{\"d\":\"CONFIRMADA\","
          + "\"q\":\"x\",\"s\":true}}",
      "{\"exp\":1,\"rota\":\"MISTO\",\"reescrita\":false,\"lei\":{\"d\":\"CONFIRMADA\","
          + "\"a\":\"minusculo\"}}",
      "{\"exp\":1,\"rota\":\"MISTO\",\"reescrita\":false,\"lei\":{\"d\":\"CONFIRMADA\","
          + "\"r\":[1,2,3]}}"})
  @DisplayName("Carga fora da forma: recusada inteira")
  void cargaForaDaForma(String json) {
    assertThat(CodecCarga.ler(json)).isEmpty();
  }

  @Test
  @DisplayName("Carga com t '*', k, a e s: ida e volta")
  void cargaComMarcas() {
    CargaTurno carga = new CargaTurno(5L, Intencao.LEGISLACAO, false, null, null, null, true,
        new LeiAssinada(LeiAssinada.Classe.SEM_CONFIRMACAO, null, List.of("LC 214/2025|art. 1"),
            null, true, true, "REFORMA"));
    assertThat(CodecCarga.ler(CodecCarga.escrever(carga).orElseThrow())).contains(carga);
  }

  // --- corte da emissao ---

  @Test
  @DisplayName("Corte: sem quebra de linha perto, corta no espaço; no MISTO, 3000 por parte")
  void cortes() {
    String semQuebra = "palavra ".repeat(900);
    String cortado = EmissaoTurno.cortarEm(semQuebra, 6000);
    assertThat(cortado).hasSizeLessThanOrEqualTo(6000).endsWith("palavra\n[…]");

    String dados = "dado ".repeat(1000);
    String lei = "Sobre a regra legal: " + "lei ".repeat(1000);
    String misto = EmissaoTurno.cortar(dados + "\n\n" + lei, Intencao.MISTO, lei);
    assertThat(misto).contains("\n[…]\n\nSobre a regra legal: ");
    assertThat(misto.length()).isLessThanOrEqualTo(6002);

    String curto = "texto curto";
    assertThat(EmissaoTurno.cortar(curto, Intencao.DADOS, null)).isEqualTo(curto);
    assertThat(EmissaoTurno.semControle(null)).isEmpty();
  }

  @Test
  @DisplayName("Corte não parte par substituto")
  void corteSemPartirSurrogate() {
    String texto = "x".repeat(5995) + "😀" + "y".repeat(100);
    String cortado = EmissaoTurno.cortarEm(texto, 6000);
    assertThat(Character.isHighSurrogate(cortado.charAt(cortado.length() - 5))).isFalse();
  }

  private static String d(String funcao, String argumentos) {
    return Autocorrecao.descrever(List.of(ChamadaAssinada.de(funcao, argumentos).orElseThrow()));
  }
}
