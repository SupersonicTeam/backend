package br.com.agrocontabil.config.avaliacao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** O gabarito é teste, nunca treino (issue #264). */
class SeparacaoTreinoTesteTest {

  private static Gabarito gabarito(String pergunta) {
    return new Gabarito("v", List.of(new PerguntaGabarito("D01",
        PerguntaGabarito.Categoria.DADOS, pergunta, null, List.of("listarNotas"), Map.of(),
        List.of(), List.of(), false, List.of(), List.of(), List.of())));
  }

  @Test
  @DisplayName("Guarda real: nenhuma pergunta do gabarito versionado está em docs/avaliacao/treino")
  void gabaritoVersionadoForaDoTreino() throws IOException {
    Gabarito real = new ObjectMapper().readValue(
        Path.of("docs/avaliacao/gabarito-ceres.json").toFile(), Gabarito.class);

    assertThat(SeparacaoTreinoTeste.contaminacoes(real, SeparacaoTreinoTeste.TREINO)).isEmpty();
    assertThat(SeparacaoTreinoTeste.contaminacoes(real, SeparacaoTreinoTeste.TREINO_EMBUTIDO))
        .isEmpty();
    // O conjunto do classificador (#265) existe de fato: a guarda nao passa por diretorio vazio.
    assertThat(SeparacaoTreinoTeste.TREINO_EMBUTIDO.resolve("classificacao-rota.json"))
        .exists();
    assertThat(SeparacaoTreinoTeste.noPromptDoClassificador(real)).isEmpty();
  }

  @Test
  @DisplayName("Guarda real: nenhuma pergunta do benchmark da banca (#352) está no treino nem no prompt")
  void perguntasDoBenchmarkForaDoTreino() throws IOException {
    ObjectMapper mapper = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    Gabarito benchmark = mapper.readValue(PerguntasBenchmarkIntegrationTest.PERGUNTAS.toFile(),
        Gabarito.class);

    assertThat(benchmark.perguntas()).hasSizeGreaterThan(80);
    assertThat(SeparacaoTreinoTeste.contaminacoes(benchmark, SeparacaoTreinoTeste.TREINO))
        .isEmpty();
    assertThat(SeparacaoTreinoTeste.contaminacoes(benchmark, SeparacaoTreinoTeste.TREINO_EMBUTIDO))
        .isEmpty();
    assertThat(SeparacaoTreinoTeste.noPromptDoClassificador(benchmark)).isEmpty();
  }

  @Test
  @DisplayName("Sem o repositório cego (#387): nenhum hash de inédita do compromisso no treino nem no prompt")
  void hashesDasIneditasForaDoTreino() throws IOException {
    JsonNode normalizadas = new ObjectMapper()
        .readTree(PerguntasBenchmarkIntegrationTest.PERGUNTAS.toFile())
        .path("ineditas").path("normalizadas");
    assertThat(normalizadas.size()).as("compromisso.normalizadas").isGreaterThanOrEqualTo(53);
    Map<Integer, Set<String>> porTamanho = new HashMap<>();
    for (JsonNode n : normalizadas) {
      assertThat(n.path("sha256").asText()).matches("[0-9a-f]{64}");
      porTamanho.computeIfAbsent(n.path("palavras").asInt(), k -> new HashSet<>())
          .add(n.path("sha256").asText());
    }
    List<String> textos = new ArrayList<>();
    textos.add(SeparacaoTreinoTeste.textoDoPrompt());
    for (Path pasta : List.of(SeparacaoTreinoTeste.TREINO, SeparacaoTreinoTeste.TREINO_EMBUTIDO)) {
      try (Stream<Path> arquivos = Files.walk(pasta)) {
        for (Path arquivo : arquivos.filter(Files::isRegularFile).toList()) {
          textos.add(Files.readString(arquivo, StandardCharsets.UTF_8));
        }
      }
    }
    assertThat(textos).hasSizeGreaterThan(1);
    List<String> achadas = new ArrayList<>();
    for (String texto : textos) {
      achadas.addAll(janelasCompromissadas(SeparacaoTreinoTeste.normalizar(texto), porTamanho));
    }
    assertThat(achadas).as("inédita copiada (cite só o hash)").isEmpty();
  }

  @Test
  @DisplayName("A guarda por hash acha a pergunta no meio do texto, com acento e pontuação diferentes")
  void hashAchaPerguntaNoMeio() throws Exception {
    String pergunta = SeparacaoTreinoTeste.normalizar("Quanto paguei de frete?");
    String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(pergunta.getBytes(StandardCharsets.UTF_8)));
    Map<Integer, Set<String>> porTamanho = Map.of(4, Set.of(sha));

    assertThat(janelasCompromissadas(
        SeparacaoTreinoTeste.normalizar("exemplo: \"QUANTO paguéi de FRETE\" fim"), porTamanho))
        .containsExactly(sha);
    assertThat(janelasCompromissadas(
        SeparacaoTreinoTeste.normalizar("quanto paguei de fretes"), porTamanho)).isEmpty();
  }

  /** Os hashes de cada janela de N palavras do texto normalizado que estão no compromisso. */
  private static List<String> janelasCompromissadas(String normalizado,
      Map<Integer, Set<String>> porTamanho) {
    String[] palavras = normalizado.isEmpty() ? new String[0] : normalizado.split(" ");
    List<String> achadas = new ArrayList<>();
    try {
      MessageDigest sha = MessageDigest.getInstance("SHA-256");
      for (Map.Entry<Integer, Set<String>> tamanho : porTamanho.entrySet()) {
        int n = tamanho.getKey();
        for (int i = 0; n > 0 && i + n <= palavras.length; i++) {
          String janela = String.join(" ", Arrays.copyOfRange(palavras, i, i + n));
          String h = HexFormat.of().formatHex(sha.digest(janela.getBytes(StandardCharsets.UTF_8)));
          if (tamanho.getValue().contains(h)) {
            achadas.add(h);
          }
        }
      }
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
    return achadas;
  }

  @Test
  @DisplayName("Com o repositório cego (#387): nenhuma inédita está no treino nem no prompt")
  void ineditasForaDoTreino() throws IOException {
    ObjectMapper mapper = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    Gabarito ineditas = mapper.treeToValue(ConjuntoBenchmark.ineditasOuPula(mapper), Gabarito.class);

    assertThat(ineditas.perguntas()).hasSizeGreaterThanOrEqualTo(53);
    assertThat(SeparacaoTreinoTeste.contaminacoes(ineditas, SeparacaoTreinoTeste.TREINO)).isEmpty();
    assertThat(SeparacaoTreinoTeste.contaminacoes(ineditas, SeparacaoTreinoTeste.TREINO_EMBUTIDO))
        .isEmpty();
    assertThat(SeparacaoTreinoTeste.noPromptDoClassificador(ineditas)).isEmpty();
  }

  @Test
  @DisplayName("Escape \\u00e9 de JSON (json.dumps do Python) não esconde a cópia")
  void escapeUnicode(@TempDir Path treino) throws IOException {
    Files.writeString(treino.resolve("exemplos.json"),
        "{\"texto\": \"Tenho algum d\\u00e9bito pendente?\"}");

    assertThat(SeparacaoTreinoTeste.contaminacoes(gabarito("Tenho algum debito pendente?"), treino))
        .extracting(SeparacaoTreinoTeste.Contaminacao::id).containsExactly("D01");
  }

  @Test
  @DisplayName("O comando recusa rodar sem achar o diretório de treino (falharia aberto)")
  void runnerSemTreino(@TempDir Path raiz) {
    assertThatThrownBy(() -> AvaliacaoCeresRunner.exigirTreinoSeparado(
        gabarito("x y"), raiz.resolve("nao-existe")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("raiz do repositorio");
  }

  @Test
  @DisplayName("Acha a pergunta copiada com outra caixa, acento e pontuação")
  void achaCopia(@TempDir Path treino) throws IOException {
    Files.writeString(treino.resolve("exemplos.jsonl"),
        "{\"texto\": \"QUANTO comprei  de fertilizánte?\", \"rota\": \"DADOS\"}\n");

    assertThat(SeparacaoTreinoTeste.contaminacoes(
        gabarito("Quanto comprei de fertilizante"), treino))
        .extracting(SeparacaoTreinoTeste.Contaminacao::id).containsExactly("D01");
  }

  @Test
  @DisplayName("Trecho de palavra não conta: 'comprei de fertilizantes' não é a pergunta")
  void palavraInteira(@TempDir Path treino) throws IOException {
    Files.writeString(treino.resolve("a.txt"), "quanto comprei de fertilizantes organicos");

    assertThat(SeparacaoTreinoTeste.contaminacoes(
        gabarito("Quanto comprei de fertilizante"), treino)).isEmpty();
  }

  @Test
  @DisplayName("Treino ausente não tem o que conferir")
  void semTreino(@TempDir Path raiz) {
    assertThat(SeparacaoTreinoTeste.contaminacoes(gabarito("x y"), raiz.resolve("nao-existe")))
        .isEmpty();
  }

  @Test
  @DisplayName("O comando de avaliação recusa gabarito contaminado")
  void runnerRecusa(@TempDir Path treino) throws IOException {
    Files.writeString(treino.resolve("a.txt"), "Quanto comprei de fertilizante?");

    assertThatThrownBy(() -> AvaliacaoCeresRunner.exigirTreinoSeparado(
        gabarito("Quanto comprei de fertilizante?"), treino))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("D01");
  }
}
