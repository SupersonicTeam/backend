package br.com.agrocontabil.config.avaliacao;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.assistente.orquestracao.Intencao;
import br.com.agrocontabil.config.avaliacao.PerguntaGabarito.Categoria;
import br.com.agrocontabil.model.DesfechoInteracao;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Consolidação das métricas da avaliação (issue #158). */
class RelatorioAvaliacaoTest {

  private static ResultadoPergunta resultado(String id, long latenciaMs, Boolean rota,
      Boolean numero, DesfechoInteracao desfecho) {
    ExecucaoPergunta execucao = new ExecucaoPergunta(Intencao.DADOS, "texto", desfecho,
        List.of(), List.of(), false, latenciaMs, null);
    return new ResultadoPergunta(id, Categoria.DADOS, execucao, List.of(), rota, null, null,
        numero, null, null, null);
  }

  private static RelatorioAvaliacao relatorio(List<ResultadoPergunta> resultados) {
    return new RelatorioAvaliacao(new RelatorioAvaliacao.Ambiente(
        OffsetDateTime.of(2026, 10, 14, 9, 0, 0, 0, ZoneOffset.ofHours(-3)),
        LocalDate.of(2026, 9, 15), "v-teste", "v9", "ollama/qwen3:4b-instruct", "ollama/bge-m3",
        List.of("CPU de teste | 8 nucleos"), 60), resultados);
  }

  @Test
  @DisplayName("Percentil pelo método nearest-rank")
  void percentil() {
    List<ResultadoPergunta> r = List.of(
        resultado("1", 1000, true, null, DesfechoInteracao.RESPONDIDA),
        resultado("2", 4000, true, null, DesfechoInteracao.RESPONDIDA),
        resultado("3", 2000, true, null, DesfechoInteracao.RESPONDIDA),
        resultado("4", 9000, true, null, DesfechoInteracao.RESPONDIDA));

    assertThat(RelatorioAvaliacao.percentil(r, 50)).isEqualTo(2000);
    assertThat(RelatorioAvaliacao.percentil(r, 95)).isEqualTo(9000);
    assertThat(RelatorioAvaliacao.percentil(List.of(), 50)).isZero();
  }

  @Test
  @DisplayName("Taxa conta só as perguntas em que o critério se aplica")
  void taxaIgnoraNaoAplicavel() {
    RelatorioAvaliacao rel = relatorio(List.of(
        resultado("1", 1, true, true, DesfechoInteracao.RESPONDIDA),
        resultado("2", 1, false, null, DesfechoInteracao.RESPONDIDA),
        resultado("3", 1, true, false, DesfechoInteracao.RESPONDIDA)));

    assertThat(rel.metricas().get("Acurácia de rota")).isEqualTo(new RelatorioAvaliacao.Taxa(2, 3));
    assertThat(rel.metricas().get("Exatidão numérica"))
        .isEqualTo(new RelatorioAvaliacao.Taxa(1, 2));
    assertThat(rel.metricas().get("Citação válida").formatada()).isEqualTo("—");
  }

  @Test
  @DisplayName("Modelo fora do ar e tempo esgotado entram como falha técnica")
  void falhaTecnica() {
    RelatorioAvaliacao rel = relatorio(List.of(
        resultado("1", 1, true, null, DesfechoInteracao.RESPONDIDA),
        resultado("2", 1, true, null, DesfechoInteracao.MODELO_INDISPONIVEL),
        resultado("3", 1, true, null, DesfechoInteracao.TEMPO_ESGOTADO)));

    assertThat(rel.metricas().get("Sem falha técnica"))
        .isEqualTo(new RelatorioAvaliacao.Taxa(1, 3));
  }

  @Test
  @DisplayName("Fallback parcial (MISTO com uma parte sem modelo) também é falha técnica")
  void fallbackParcialEFalhaTecnica() {
    ExecucaoPergunta parcial = new ExecucaoPergunta(Intencao.MISTO, "texto",
        DesfechoInteracao.RESPONDIDA, List.of(), List.of(), true, 1, null);

    assertThat(RelatorioAvaliacao.falhaTecnica(parcial)).isTrue();
  }

  @Test
  @DisplayName("MISTO: o desfecho de cada parte aparece ao lado do desfecho final")
  void partesDoMisto() {
    ExecucaoPergunta misto = new ExecucaoPergunta(Intencao.MISTO, "texto",
        DesfechoInteracao.RESPONDIDA, List.of(), List.of(), false, 1, null,
        DesfechoInteracao.RESPONDIDA, DesfechoInteracao.CITACAO_INVALIDA, true, "LLM");
    ExecucaoPergunta dados = new ExecucaoPergunta(Intencao.DADOS, "texto",
        DesfechoInteracao.RESPONDIDA, List.of(), List.of(), false, 1, null);

    assertThat(RelatorioAvaliacao.partesMisto(misto))
        .isEqualTo(" (dados RESPONDIDA, lei CITACAO_INVALIDA, reescrita)");
    assertThat(RelatorioAvaliacao.partesMisto(dados)).isEmpty();
  }

  @Test
  @DisplayName("Markdown traz condições, métricas e uma linha por pergunta")
  void markdown() {
    String md = relatorio(List.of(
        resultado("D01", 1500, true, true, DesfechoInteracao.RESPONDIDA),
        resultado("D02", 2500, true, false, DesfechoInteracao.RESPONDIDA))).markdown();

    assertThat(md).contains("ollama/qwen3:4b-instruct", "2026-09-15", "v-teste", "| Seed | v9 |");
    assertThat(md).contains("| Exatidão numérica | 50.0% (1/2) |");
    assertThat(md).contains("| D01 | DADOS | RESPONDIDA | ✓ |", "| D02 |");
    // Barra vertical no hardware não pode quebrar a tabela.
    assertThat(md).contains("CPU de teste \\| 8 nucleos");
  }

  @Test
  @DisplayName("#265: precisao por camada, com a parcela de cada uma no total")
  void porCamada() {
    List<ResultadoPergunta> resultados = List.of(
        comCamada("a", "REGRA", true), comCamada("b", "REGRA", true),
        comCamada("c", "EMBEDDING", false), comCamada("d", "LLM", true));

    var porCamada = relatorio(resultados).porCamada();

    assertThat(porCamada.keySet()).containsExactly("REGRA", "EMBEDDING", "LLM");
    assertThat(porCamada.get("REGRA")).containsExactly(2, 2);
    assertThat(porCamada.get("EMBEDDING")).containsExactly(1, 0);
    assertThat(relatorio(resultados).markdown())
        .contains("## Por camada da classificação (#265)")
        .contains("| REGRA | 2 | 2 | 0 | 50% |")
        .contains("| EMBEDDING | 1 | 0 | 1 | 25% |");
  }

  private static ResultadoPergunta comCamada(String id, String camada, boolean acertou) {
    ExecucaoPergunta execucao = new ExecucaoPergunta(Intencao.DADOS, "texto",
        DesfechoInteracao.RESPONDIDA, List.of(), List.of(), false, 1, null, null, null, null,
        camada);
    return new ResultadoPergunta(id, Categoria.DADOS, execucao, List.of(), acertou, null, null,
        null, null, null, null);
  }
}
