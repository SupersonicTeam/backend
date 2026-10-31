package br.com.agrocontabil.config.avaliacao;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.assistente.orquestracao.Intencao;
import br.com.agrocontabil.config.avaliacao.PerguntaGabarito.Categoria;
import br.com.agrocontabil.model.DesfechoInteracao;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** CSV cego da nota humana de clareza (issue #264). */
class CsvCegoTest {

  private static ResultadoPergunta resultado(String id, String texto) {
    return new ResultadoPergunta(id, Categoria.DADOS, new ExecucaoPergunta(Intencao.DADOS, texto,
        DesfechoInteracao.RESPONDIDA, List.of(), List.of(), false, 10, null), List.of(), true,
        null, null, null, null, null, null, Intencao.DADOS, false);
  }

  private static RelatorioAvaliacao rodada(List<ResultadoPergunta> resultados) {
    return new RelatorioAvaliacao(new RelatorioAvaliacao.Ambiente(
        OffsetDateTime.of(2026, 10, 31, 9, 0, 0, 0, ZoneOffset.ofHours(-3)),
        LocalDate.of(2026, 9, 15), "g", "s", "m", "e", List.of(), 1), resultados);
  }

  @Test
  @DisplayName("Sem rota nem rodada no CSV; a chave liga cada item à pergunta e à rodada")
  void cego() {
    RelatorioRepeticoes repeticoes = new RelatorioRepeticoes(List.of(
        rodada(List.of(resultado("D01", "Resposta \"um\", com virgula"), resultado("D02", null))),
        rodada(List.of(resultado("D01", "=SOMA(A1)"), resultado("D02", "Outra")))));

    CsvCego.Instrumento cego = CsvCego.gerar(repeticoes,
        Map.of("D01", "Quanto comprei?", "D02", "Quanto vendi?"), new Random(7));

    String[] linhas = cego.csv().split("\r\n");
    assertThat(linhas[0]).isEqualTo(CsvCego.CABECALHO);
    // A resposta nula (falha) não entra: 3 linhas de dados.
    assertThat(linhas).hasSize(4);
    assertThat(cego.csv()).doesNotContain("DADOS", "D01", "D02", "rodada")
        .contains("\"Resposta \"\"um\"\", com virgula\"")
        .contains("\"'=SOMA(A1)\"");
    assertThat(cego.chave()).extracting(CsvCego.Chave::item).containsExactly(1, 2, 3);
    assertThat(cego.chave()).extracting(c -> c.id() + "/" + c.rodada())
        .containsExactlyInAnyOrder("D01/1", "D01/2", "D02/2");
  }
}
