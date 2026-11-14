package br.com.agrocontabil.config.avaliacao;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.assistente.SinalRecusaFirme;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A recusa antes do classificador (#497) contra os conjuntos de avaliação, lidos só em tempo de
 * execução (teste cego): nenhuma pergunta de dados, de lei ou mista é recusada por
 * {@link SinalRecusaFirme#inequivoco}. A mensagem de falha mostra só o id da pergunta.
 */
class SinalRecusaFirmeConjuntosTest {

  private static final ObjectMapper MAPPER = new ObjectMapper()
      .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  private static final Path GABARITO = Path.of("docs/avaliacao/gabarito-ceres.json");

  /** As categorias que a Ceres responde: recusar uma delas é recusa indevida. */
  private static final Set<PerguntaGabarito.Categoria> RESPONDIDAS = Set.of(
      PerguntaGabarito.Categoria.DADOS, PerguntaGabarito.Categoria.LEGISLACAO,
      PerguntaGabarito.Categoria.MISTO);

  @Test
  @DisplayName("gabarito e benchmark: nenhuma pergunta respondida é recusada antes do"
      + " classificador")
  void gabaritoEBenchmark() throws IOException {
    assertThat(recusadas(MAPPER.readValue(GABARITO.toFile(), Gabarito.class)))
        .as("ids do gabarito recusados").isEmpty();
    assertThat(recusadas(MAPPER.readValue(ConjuntoBenchmark.PERGUNTAS.toFile(), Gabarito.class)))
        .as("ids do benchmark recusados").isEmpty();
  }

  @Test
  @DisplayName("conjunto cego: nenhuma inédita respondida é recusada antes do classificador")
  void conjuntoCego() throws IOException {
    assertThat(recusadas(
        MAPPER.treeToValue(ConjuntoBenchmark.ineditasOuPula(MAPPER), Gabarito.class)))
        .as("ids das inéditas recusados").isEmpty();
  }

  private static List<String> recusadas(Gabarito conjunto) {
    return conjunto.perguntas().stream()
        .filter(p -> RESPONDIDAS.contains(p.categoria()))
        .filter(p -> SinalRecusaFirme.inequivoco(p.pergunta()))
        .map(PerguntaGabarito::id)
        .toList();
  }
}
