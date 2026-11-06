package br.com.agrocontabil.config.avaliacao;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Assumptions;

/**
 * O conjunto do benchmark da banca (#352) com as inéditas do repositório cego (#387).
 *
 * <p>O backend guarda as herdadas do gabarito e o compromisso das inéditas ({@code ineditas}:
 * arquivo, versão, sha256 e ids); o texto delas fica só no {@code SupersonicTeam/ceres-benchmark-cego}.
 * O checkout dele vem de {@code CERES_BENCHMARK_CEGO} (ou da propriedade {@code benchmark.cego}) ou
 * da pasta irmã {@code ../ceres-benchmark-cego}. Sem ele, os testes das inéditas pulam com
 * "conjunto cego ausente"; com {@code CERES_BENCHMARK_CEGO_EXIGIR=1} (a CI do repositório cego), a
 * ausência falha. Com ele, o sha256, a versão e os ids têm de bater com o compromisso.
 */
final class ConjuntoBenchmark {

  static final Path PERGUNTAS = Path.of("docs/avaliacao/benchmark/perguntas-benchmark.json");
  static final String AUSENTE = "conjunto cego ausente";

  private ConjuntoBenchmark() {}

  /** O checkout do repositório cego, se existir. */
  static Optional<Path> pastaCego() {
    String escolhido = System.getProperty("benchmark.cego", System.getenv("CERES_BENCHMARK_CEGO"));
    Path pasta = escolhido != null && !escolhido.isBlank()
        ? Path.of(escolhido) : Path.of("..", "ceres-benchmark-cego");
    return Files.isDirectory(pasta) ? Optional.of(pasta) : Optional.empty();
  }

  /** O conjunto do backend (as herdadas e o compromisso). */
  static JsonNode herdadas(ObjectMapper mapper) throws IOException {
    return mapper.readTree(PERGUNTAS.toFile());
  }

  /**
   * As inéditas do repositório cego, conferidas contra o compromisso; pula o teste (ou falha, com
   * {@code CERES_BENCHMARK_CEGO_EXIGIR=1}) sem o repositório.
   */
  static JsonNode ineditasOuPula(ObjectMapper mapper) throws IOException {
    JsonNode compromisso = herdadas(mapper).path("ineditas");
    Optional<Path> pasta = pastaCego();
    Path arquivo = pasta.map(p -> p.resolve(compromisso.path("arquivo").asText("ineditas.json")))
        .filter(Files::exists).orElse(null);
    if (arquivo == null) {
      if ("1".equals(System.getenv("CERES_BENCHMARK_CEGO_EXIGIR"))) {
        throw new IllegalStateException(AUSENTE + ": CERES_BENCHMARK_CEGO_EXIGIR=1");
      }
      Assumptions.abort(AUSENTE + " (SupersonicTeam/ceres-benchmark-cego ao lado do backend ou "
          + "CERES_BENCHMARK_CEGO): as inéditas não estão neste repositório");
    }
    byte[] bytes = Files.readAllBytes(arquivo);
    String sha;
    try {
      sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
    if (!sha.equals(compromisso.path("sha256").asText())) {
      throw new AssertionError("o conjunto cego (" + sha.substring(0, 12) + ") não confere com o "
          + "compromisso do backend (" + compromisso.path("sha256").asText() + "): atualize o "
          + "compromisso (versão e sha256) em perguntas-benchmark.json ou o checkout do cego");
    }
    JsonNode ineditas = mapper.readTree(bytes);
    if (!ineditas.path("versao").asText().equals(compromisso.path("versao").asText())) {
      throw new AssertionError("versão do conjunto cego diferente da do compromisso");
    }
    List<String> ids = new ArrayList<>();
    ineditas.path("perguntas").forEach(p -> ids.add(p.path("id").asText()));
    List<String> prometidos = new ArrayList<>();
    compromisso.path("ids").forEach(i -> prometidos.add(i.asText()));
    if (!ids.equals(prometidos)) {
      throw new AssertionError("ids do conjunto cego diferentes dos do compromisso");
    }
    return ineditas;
  }

  /** O conjunto inteiro (herdadas + inéditas), como o executor o monta; pula sem o cego. */
  static ObjectNode completoOuPula(ObjectMapper mapper) throws IOException {
    ObjectNode completo = (ObjectNode) herdadas(mapper).deepCopy();
    ArrayNode perguntas = (ArrayNode) completo.path("perguntas");
    ineditasOuPula(mapper).path("perguntas").forEach(perguntas::add);
    return completo;
  }
}
