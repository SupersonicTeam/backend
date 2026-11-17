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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * O conjunto de medição da #443 (ressalva do enquadramento com o contador na resposta sobre o
 * tratamento de um produto) é conjunto de teste: nunca está no treino nem no prompt do
 * classificador, e o hash versionado é o dos bytes.
 */
class ConjuntoRessalvaTratamentoTest {

  private static final Path CONJUNTO =
      Path.of("docs/avaliacao/ressalva-tratamento-443/perguntas-ressalva-443.json");

  @Test
  @DisplayName("Guarda real: nenhuma pergunta do conjunto da #443 está no treino nem no prompt")
  void conjuntoForaDoTreinoEDoPrompt() throws IOException {
    JsonNode raiz = new ObjectMapper().readTree(CONJUNTO.toFile());
    JsonNode perguntas = raiz.path("perguntas");
    assertThat(perguntas.size()).isEqualTo(60);

    List<PerguntaGabarito> lista = new ArrayList<>();
    for (JsonNode p : perguntas) {
      lista.add(new PerguntaGabarito(p.path("id").asText(), PerguntaGabarito.Categoria.LEGISLACAO,
          p.path("pergunta").asText(), null, List.of(), Map.of(), List.of(), List.of(), false,
          List.of(), List.of(), List.of()));
    }
    Gabarito conjunto = new Gabarito(raiz.path("versao").asText(), lista);

    assertThat(SeparacaoTreinoTeste.contaminacoes(conjunto, SeparacaoTreinoTeste.TREINO)).isEmpty();
    assertThat(SeparacaoTreinoTeste.contaminacoes(conjunto, SeparacaoTreinoTeste.TREINO_EMBUTIDO))
        .isEmpty();
    assertThat(SeparacaoTreinoTeste.noPromptDoClassificador(conjunto)).isEmpty();
  }

  @Test
  @DisplayName("O sha256 versionado é o dos bytes do conjunto congelado")
  void hashRegistradoConfere() throws IOException, NoSuchAlgorithmException {
    String registrado =
        Files.readString(Path.of(CONJUNTO + ".sha256")).trim().split("\s+")[0];
    String real = java.util.HexFormat.of()
        .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(CONJUNTO)));

    assertThat(real).as("sha256 do conjunto (subiu a versão? grave o novo hash)")
        .isEqualTo(registrado.toLowerCase());
  }
}
