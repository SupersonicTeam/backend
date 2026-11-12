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
import org.junit.jupiter.api.Test;

/**
 * O conjunto de perguntas da medição de fidelidade da rota de lei (#269) é conjunto de teste: nunca
 * está no treino nem no prompt do classificador, e o hash versionado é o dos bytes.
 */
class ConjuntoFidelidadeLeiTest {

  private static final Path PASTA = Path.of("docs/avaliacao/fidelidade-lei");
  private static final Path CONJUNTO = PASTA.resolve("perguntas-fidelidade-lei.json");

  @Test
  @DisplayName("Guarda real: nenhuma pergunta do conjunto de fidelidade está no treino nem no prompt")
  void conjuntoForaDoTreinoEDoPrompt() throws IOException {
    JsonNode perguntas = new ObjectMapper().readTree(CONJUNTO.toFile()).path("perguntas");
    assertThat(perguntas.size()).isEqualTo(36);

    List<PerguntaGabarito> lista = new ArrayList<>();
    for (JsonNode p : perguntas) {
      lista.add(new PerguntaGabarito(p.path("id").asText(), PerguntaGabarito.Categoria.LEGISLACAO,
          p.path("pergunta").asText(), null, List.of(), java.util.Map.of(), List.of(), List.of(),
          false, List.of(), List.of(), List.of()));
    }
    Gabarito conjunto = new Gabarito(new ObjectMapper().readTree(CONJUNTO.toFile())
        .path("versao").asText(), lista);

    assertThat(SeparacaoTreinoTeste.contaminacoes(conjunto, SeparacaoTreinoTeste.TREINO)).isEmpty();
    assertThat(SeparacaoTreinoTeste.contaminacoes(conjunto, SeparacaoTreinoTeste.TREINO_EMBUTIDO))
        .isEmpty();
    assertThat(SeparacaoTreinoTeste.noPromptDoClassificador(conjunto)).isEmpty();
  }

  @Test
  @DisplayName("O sha256 versionado é o dos bytes do conjunto congelado")
  void hashRegistradoConfere() throws IOException, NoSuchAlgorithmException {
    String registrado = Files.readString(Path.of(CONJUNTO + ".sha256")).trim().split("\\s+")[0];
    String real = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(CONJUNTO)));

    assertThat(real).as("sha256 do conjunto (subiu a versão? grave o novo hash)")
        .isEqualTo(registrado.toLowerCase());
  }
}
