package br.com.agrocontabil.config.avaliacao;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.assistente.orquestracao.ConversaLeve;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A conversa leve (#493) contra os conjuntos de avaliação, lidos só em tempo de execução (teste
 * cego): nenhuma pergunta fora da categoria {@code FORA_DE_ESCOPO} cai nela, nenhum texto ou valor
 * proibido aparece nos textos novos, e os exemplos dos textos estão no prompt. A mensagem de falha
 * mostra só o id da pergunta.
 */
class ConversaLeveConjuntosTest {

  private static final ObjectMapper MAPPER = new ObjectMapper()
      .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  private static final Path GABARITO = Path.of("docs/avaliacao/gabarito-ceres.json");

  @Test
  @DisplayName("gabarito e benchmark: nenhuma pergunta fora de FORA_DE_ESCOPO é conversa leve")
  void gabaritoEBenchmark() throws IOException {
    conferir(MAPPER.readValue(GABARITO.toFile(), Gabarito.class));
    conferir(MAPPER.readValue(ConjuntoBenchmark.PERGUNTAS.toFile(), Gabarito.class));
  }

  @Test
  @DisplayName("conjunto cego: nenhuma inédita fora de FORA_DE_ESCOPO é conversa leve")
  void conjuntoCego() throws IOException {
    conferir(MAPPER.treeToValue(ConjuntoBenchmark.ineditasOuPula(MAPPER), Gabarito.class));
  }

  @Test
  @DisplayName("os exemplos dos textos estão no prompt (cópia literal das descrições das funções)")
  void exemplosNoPrompt() {
    String prompt = " " + SeparacaoTreinoTeste.normalizar(SeparacaoTreinoTeste.textoDoPrompt())
        + " ";
    Pattern linha = Pattern.compile("^- (.+)$", Pattern.MULTILINE);
    List<String> exemplos = new ArrayList<>();
    ConversaLeve.textos().values().forEach(texto -> {
      Matcher m = linha.matcher(texto);
      while (m.find()) {
        exemplos.add(m.group(1));
      }
    });
    assertThat(exemplos).isNotEmpty();
    exemplos.forEach(exemplo -> assertThat(prompt)
        .as(exemplo).contains(" " + SeparacaoTreinoTeste.normalizar(exemplo) + " "));
  }

  private static void conferir(Gabarito conjunto) {
    List<String> textos = ConversaLeve.textos().values().stream()
        .map(t -> " " + SeparacaoTreinoTeste.normalizar(t) + " ").toList();
    List<String> reconhecidas = new ArrayList<>();
    List<String> proibidosNosTextos = new ArrayList<>();
    for (PerguntaGabarito p : conjunto.perguntas()) {
      if (p.categoria() != PerguntaGabarito.Categoria.FORA_DE_ESCOPO
          && ConversaLeve.ehConversaLeve(p.pergunta())) {
        reconhecidas.add(p.id());
      }
      List<String> proibidos = new ArrayList<>(p.textosProibidos());
      proibidos.addAll(p.valoresProibidos());
      for (String proibido : proibidos) {
        String n = SeparacaoTreinoTeste.normalizar(proibido);
        if (!n.isEmpty() && textos.stream().anyMatch(t -> t.contains(" " + n + " "))) {
          proibidosNosTextos.add(p.id());
        }
      }
    }
    assertThat(reconhecidas).as("ids reconhecidos como conversa leve").isEmpty();
    assertThat(proibidosNosTextos).as("ids com texto ou valor proibido nos textos").isEmpty();
  }
}
