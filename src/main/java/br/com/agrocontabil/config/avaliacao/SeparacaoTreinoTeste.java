package br.com.agrocontabil.config.avaliacao;

import br.com.agrocontabil.assistente.PromptsCeres;
import br.com.agrocontabil.assistente.ferramentas.FerramentasDadosProdutor;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * O gabarito é <strong>teste</strong>, nunca treino (issue #264, da #201): nenhuma pergunta do
 * gabarito pode aparecer em {@code docs/avaliacao/treino/}, onde ficam os exemplos de
 * classificação da #265. Uma pergunta do gabarito usada como exemplo mede memória, não acerto.
 *
 * <p>A comparação é pelo texto normalizado (sem acento, sem caixa, sem pontuação, espaços
 * simples), então "Quanto comprei de fertilizante?" e "quanto  comprei de FERTILIZANTE" são a
 * mesma pergunta; escapes {@code \\u00e9} de JSON são lidos antes. Paráfrase não é detectável
 * por texto: a regra do README proíbe também a paráfrase, e a revisão do PR é quem a pega.
 *
 * <p>Além do diretório, os exemplos que o modelo vê de verdade ficam no prompt do classificador
 * ({@link PromptsCeres#CLASSIFICACAO}) e nas descrições {@code @Tool} das funções de dados, e passam
 * pela mesma conferência.
 */
public final class SeparacaoTreinoTeste {

  /** Diretório dos exemplos de treino. */
  public static final Path TREINO = Path.of("docs/avaliacao/treino");

  /**
   * Treino que vai dentro do JAR (#265): o conjunto do classificador de rota precisa estar no
   * classpath, e a imagem só copia {@code src}. Mesma guarda que o diretório de treino.
   */
  public static final Path TREINO_EMBUTIDO = Path.of("src/main/resources/ceres/treino");

  private static final Pattern ACENTOS = Pattern.compile("\\p{M}");
  private static final Pattern ESCAPE_UNICODE = Pattern.compile("\\\\u([0-9a-fA-F]{4})");
  private static final Pattern NAO_ALFANUMERICO = Pattern.compile("[^a-z0-9]+");

  private SeparacaoTreinoTeste() {}

  /**
   * Uma pergunta do gabarito encontrada num arquivo de treino.
   *
   * @param id      id da pergunta
   * @param arquivo arquivo de treino que a contém
   */
  public record Contaminacao(String id, Path arquivo) {}

  /**
   * Perguntas do gabarito presentes nos arquivos de treino.
   *
   * @param gabarito gabarito
   * @param treino   diretório de treino (ausente = nada a conferir)
   * @return as contaminações, vazio se o treino está limpo
   */
  public static List<Contaminacao> contaminacoes(Gabarito gabarito, Path treino) {
    if (!Files.isDirectory(treino)) {
      return List.of();
    }
    List<Contaminacao> encontradas = new ArrayList<>();
    try (Stream<Path> arquivos = Files.walk(treino)) {
      for (Path arquivo : arquivos.filter(Files::isRegularFile).sorted().toList()) {
        String conteudo = " " + normalizar(ler(arquivo)) + " ";
        for (PerguntaGabarito p : gabarito.perguntas()) {
          String pergunta = normalizar(p.pergunta());
          if (!pergunta.isEmpty() && conteudo.contains(" " + pergunta + " ")) {
            encontradas.add(new Contaminacao(p.id(), arquivo));
          }
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return encontradas;
  }

  /**
   * Perguntas do gabarito copiadas nos exemplos do prompt do classificador ou das descrições das
   * funções de dados (que também são prompt).
   *
   * @param gabarito gabarito
   * @return ids das perguntas encontradas no prompt
   */
  public static List<String> noPromptDoClassificador(Gabarito gabarito) {
    String prompt = " " + normalizar(textoDoPrompt()) + " ";
    return gabarito.perguntas().stream()
        .filter(p -> prompt.contains(" " + normalizar(p.pergunta()) + " "))
        .map(PerguntaGabarito::id)
        .toList();
  }

  /**
   * O texto que vai ao modelo como prompt: o do classificador e as descrições das funções de
   * dados.
   *
   * @return o texto, sem normalizar
   */
  static String textoDoPrompt() {
    StringBuilder textos = new StringBuilder(PromptsCeres.CLASSIFICACAO);
    // Os prompts da continuidade (#499) tambem vao ao modelo.
    textos.append(' ').append(PromptsCeres.REESCRITA_CONTINUIDADE)
        .append(' ').append(PromptsCeres.sistemaReconferenciaLei(
            java.time.LocalDate.of(2026, 1, 1), ""));
    // As descricoes das funcoes tambem vao ao modelo como prompt, com exemplos de pergunta.
    for (Method metodo : FerramentasDadosProdutor.class.getDeclaredMethods()) {
      Tool funcao = metodo.getAnnotation(Tool.class);
      if (funcao != null) {
        textos.append(' ').append(funcao.description());
        for (Parameter parametro : metodo.getParameters()) {
          ToolParam descricao = parametro.getAnnotation(ToolParam.class);
          if (descricao != null) {
            textos.append(' ').append(descricao.description());
          }
        }
      }
    }
    return textos.toString();
  }

  /** Sem acento, minúsculas, só letras e dígitos separados por um espaço. */
  static String normalizar(String texto) {
    String semEscape = ESCAPE_UNICODE.matcher(texto).replaceAll(
        m -> Matcher.quoteReplacement(String.valueOf((char) Integer.parseInt(m.group(1), 16))));
    String semAcento = ACENTOS.matcher(Normalizer.normalize(semEscape, Normalizer.Form.NFD))
        .replaceAll("").toLowerCase(Locale.ROOT);
    return NAO_ALFANUMERICO.matcher(semAcento).replaceAll(" ").trim();
  }

  private static String ler(Path arquivo) throws IOException {
    return new String(Files.readAllBytes(arquivo), StandardCharsets.UTF_8);
  }
}
