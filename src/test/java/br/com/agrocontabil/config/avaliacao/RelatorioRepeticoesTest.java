package br.com.agrocontabil.config.avaliacao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.agrocontabil.assistente.orquestracao.Intencao;
import br.com.agrocontabil.config.avaliacao.PerguntaGabarito.Categoria;
import br.com.agrocontabil.model.DesfechoInteracao;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Avaliação com repetições (issue #264, absorve a #201). */
class RelatorioRepeticoesTest {

  private static ResultadoPergunta resultado(String id, Intencao rotaEsperada, boolean adversario,
      Boolean rota, Boolean numero, long latenciaMs, String texto) {
    ExecucaoPergunta execucao = new ExecucaoPergunta(Intencao.DADOS, texto,
        DesfechoInteracao.RESPONDIDA, List.of(), List.of(), false, latenciaMs, null);
    return new ResultadoPergunta(id, Categoria.DADOS, execucao, List.of(), rota, null, null,
        numero, null, null, null, rotaEsperada, adversario);
  }

  private static RelatorioAvaliacao rodada(String gabarito, ResultadoPergunta... resultados) {
    return new RelatorioAvaliacao(new RelatorioAvaliacao.Ambiente(
        OffsetDateTime.of(2026, 10, 31, 9, 0, 0, 0, ZoneOffset.ofHours(-3)),
        LocalDate.of(2026, 9, 15), gabarito, "v2", "ollama/qwen3:4b-instruct", "ollama/bge-m3",
        List.of("CPU"), 10), List.of(resultados));
  }

  private static RelatorioRepeticoes tres() {
    return new RelatorioRepeticoes(List.of(
        rodada("g", resultado("D01", Intencao.DADOS, false, true, true, 1000, "Voce comprou."),
            resultado("X01", Intencao.LEGISLACAO, true, true, null, 3000, "O IBS vale.")),
        rodada("g", resultado("D01", Intencao.DADOS, false, true, false, 2000, "Voce comprou."),
            resultado("X01", Intencao.LEGISLACAO, true, false, null, 4000, "O IBS vale.")),
        rodada("g", resultado("D01", Intencao.DADOS, false, true, true, 9000, "Voce comprou."),
            resultado("X01", Intencao.LEGISLACAO, true, true, null, 5000, "O IBS vale."))));
  }

  @Test
  @DisplayName("Média e faixa entre as rodadas")
  void mediaEFaixa() {
    RelatorioRepeticoes r = tres();

    RelatorioRepeticoes.Faixa rota = r.metricas().get("Acurácia de rota");
    assertThat(rota.media()).isCloseTo(83.33, org.assertj.core.data.Offset.offset(0.01));
    assertThat(rota.minimo()).isEqualTo(50.0);
    assertThat(rota.maximo()).isEqualTo(100.0);
    assertThat(rota.formatada()).isEqualTo("83.3% (50.0–100.0%, n=2)");
    assertThat(r.metricas().get("Rota nos casos adversários").media())
        .isCloseTo(66.67, org.assertj.core.data.Offset.offset(0.01));
    assertThat(r.metricas().get("Citação válida").formatada()).isEqualTo("—");
  }

  @Test
  @DisplayName("Instáveis: a pergunta e os critérios que mudaram entre rodadas")
  void instaveis() {
    assertThat(tres().instaveis())
        .containsEntry("D01", List.of("número"))
        .containsEntry("X01", List.of("rota"))
        .hasSize(2);
  }

  @Test
  @DisplayName("Por rota: latência agregada sobre as amostras de todas as rodadas")
  void porRotaAgregado() {
    RelatorioAvaliacao.IndicadoresRota dados = tres().porRotaAgregado().get("DADOS");

    assertThat(dados.perguntas()).isEqualTo(3);
    assertThat(dados.latenciaP50Ms()).isEqualTo(2000);
    assertThat(dados.latenciaP95Ms()).isEqualTo(9000);
    assertThat(dados.primeiraParteP50Ms()).isEqualTo(2000);
    assertThat(dados.respostasMedidas()).isEqualTo(3);
    RelatorioAvaliacao.IndicadoresRota lei = tres().porRotaAgregado().get("LEGISLACAO");
    assertThat(lei.comSiglaSemExplicacao()).isEqualTo(3);
  }

  @Test
  @DisplayName("Rodadas de gabaritos diferentes não se comparam")
  void recusaGabaritosDiferentes() {
    assertThatThrownBy(() -> new RelatorioRepeticoes(List.of(
        rodada("g1", resultado("D01", Intencao.DADOS, false, true, true, 1, "a")),
        rodada("g2", resultado("D01", Intencao.DADOS, false, true, true, 1, "a")))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Markdown: condições, faixa, por rota, instáveis e cada rodada")
  void markdown() {
    String md = tres().markdown();

    assertThat(md).contains("3 rodadas", "| Repetições | 3 |", "| Seed | v2 |",
        "| Acurácia de rota | 83.3% (50.0–100.0%, n=2) |",
        "## Por rota esperada", "| DADOS |", "| LEGISLACAO |",
        "## Perguntas instáveis entre rodadas", "| D01 | número |",
        "## Rodada 1", "## Rodada 3", "### Métricas");
  }

  @Test
  @DisplayName("Uma rodada só: sem comparação, e o relatório diz isso")
  void umaRodada() {
    RelatorioRepeticoes uma = new RelatorioRepeticoes(List.of(
        rodada("g", resultado("D01", Intencao.DADOS, false, true, true, 1, "a"))));

    assertThat(uma.instaveis()).isEmpty();
    assertThat(uma.markdown()).contains("Uma rodada só", "1 rodada");
  }
}
