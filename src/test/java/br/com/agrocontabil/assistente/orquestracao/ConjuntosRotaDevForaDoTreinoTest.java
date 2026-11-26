package br.com.agrocontabil.assistente.orquestracao;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.common.NormalizacaoBusca;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Os conjuntos de desenvolvimento do classificador de rota ({@code docs/avaliacao/rota-dev/}, #497
 * e #624) nunca entram no treino: o diagnóstico mediria o próprio treino. Confere a pergunta
 * normalizada (sem acento, caixa nem pontuação); paráfrase fica com quem escreve o treino.
 */
class ConjuntosRotaDevForaDoTreinoTest {

  private static final Path PASTA = Path.of("docs/avaliacao/rota-dev");

  @Test
  @DisplayName("#624: nenhuma pergunta dos conjuntos de desenvolvimento está no treino da rota")
  void foraDoTreino() throws IOException {
    Set<String> treino = EmbeddingClassificadorIntencao.carregar(new ObjectMapper()).stream()
        .map(e -> normalizada(e.pergunta()))
        .collect(Collectors.toSet());
    List<Path> conjuntos = new ArrayList<>();
    try (DirectoryStream<Path> arquivos =
        Files.newDirectoryStream(PASTA, "perguntas-rota-dev*.json")) {
      arquivos.forEach(conjuntos::add);
    }
    assertThat(conjuntos).as("conjuntos de desenvolvimento").hasSizeGreaterThanOrEqualTo(2);

    List<String> noTreino = new ArrayList<>();
    for (Path conjunto : conjuntos) {
      JsonNode raiz = new ObjectMapper().readTree(conjunto.toFile());
      assertThat(raiz.path("perguntas").size()).as(conjunto.getFileName().toString()).isPositive();
      for (JsonNode p : raiz.path("perguntas")) {
        if (treino.contains(normalizada(p.path("pergunta").asText()))) {
          noTreino.add(conjunto.getFileName() + ":" + p.path("id").asText());
        }
      }
    }
    assertThat(noTreino).as("perguntas de desenvolvimento copiadas no treino").isEmpty();
  }

  private static String normalizada(String texto) {
    return NormalizacaoBusca.normalizar(texto).replaceAll("[^a-z0-9 ]+", " ")
        .replaceAll("\\s+", " ").strip();
  }
}
