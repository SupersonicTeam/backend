package br.com.agrocontabil.config.avaliacao;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Os conjuntos de perguntas da medição de fidelidade da rota de lei (#269), o v1 e o v2 (segunda
 * medição, escrito depois do ajuste da correção), são conjuntos de teste: nunca estão no treino nem
 * no prompt do classificador, e o hash versionado é o dos bytes.
 */
class ConjuntoFidelidadeLeiTest {

  private static final Path PASTA = Path.of("docs/avaliacao/fidelidade-lei");

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"perguntas-fidelidade-lei.json", "perguntas-fidelidade-lei-v2.json"})
  @DisplayName("Guarda real: nenhuma pergunta do conjunto de fidelidade está no treino nem no prompt")
  void conjuntoForaDoTreinoEDoPrompt(String arquivo) throws IOException {
    Path conjuntoArquivo = PASTA.resolve(arquivo);
    JsonNode raiz = new ObjectMapper().readTree(conjuntoArquivo.toFile());
    JsonNode perguntas = raiz.path("perguntas");
    assertThat(perguntas.size()).isEqualTo(36);

    List<PerguntaGabarito> lista = new ArrayList<>();
    for (JsonNode p : perguntas) {
      lista.add(new PerguntaGabarito(p.path("id").asText(), PerguntaGabarito.Categoria.LEGISLACAO,
          p.path("pergunta").asText(), null, List.of(), java.util.Map.of(), List.of(), List.of(),
          false, List.of(), List.of(), List.of()));
    }
    Gabarito conjunto = new Gabarito(raiz.path("versao").asText(), lista);

    assertThat(SeparacaoTreinoTeste.contaminacoes(conjunto, SeparacaoTreinoTeste.TREINO)).isEmpty();
    assertThat(SeparacaoTreinoTeste.contaminacoes(conjunto, SeparacaoTreinoTeste.TREINO_EMBUTIDO))
        .isEmpty();
    assertThat(SeparacaoTreinoTeste.noPromptDoClassificador(conjunto)).isEmpty();
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"perguntas-fidelidade-lei.json", "perguntas-fidelidade-lei-v2.json"})
  @DisplayName("O sha256 versionado é o dos bytes do conjunto congelado")
  void hashRegistradoConfere(String arquivo) throws IOException, NoSuchAlgorithmException {
    Path conjunto = PASTA.resolve(arquivo);
    String registrado = Files.readString(Path.of(conjunto + ".sha256")).trim().split("\\s+")[0];
    String real = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(conjunto)));

    assertThat(real).as("sha256 do conjunto (subiu a versão? grave o novo hash)")
        .isEqualTo(registrado.toLowerCase());
  }
}
