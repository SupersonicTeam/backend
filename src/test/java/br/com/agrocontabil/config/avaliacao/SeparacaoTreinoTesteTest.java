package br.com.agrocontabil.config.avaliacao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
    assertThat(SeparacaoTreinoTeste.noPromptDoClassificador(real)).isEmpty();
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
