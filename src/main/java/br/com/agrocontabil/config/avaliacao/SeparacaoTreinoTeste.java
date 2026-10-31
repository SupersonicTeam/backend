package br.com.agrocontabil.config.avaliacao;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * O gabarito é <strong>teste</strong>, nunca treino (issue #264, da #201): nenhuma pergunta do
 * gabarito pode aparecer em {@code docs/avaliacao/treino/}, onde ficam os exemplos de
 * classificação da #265. Uma pergunta do gabarito usada como exemplo mede memória, não acerto.
 *
 * <p>A comparação é pelo texto normalizado (sem acento, sem caixa, sem pontuação, espaços
 * simples), então "Quanto comprei de fertilizante?" e "quanto  comprei de FERTILIZANTE" são a
 * mesma pergunta. Paráfrase não é detectável por texto: a regra do README proíbe também a
 * paráfrase, e a revisão do PR é quem a pega.
 */
public final class SeparacaoTreinoTeste {

  /** Diretório dos exemplos de treino. */
  public static final Path TREINO = Path.of("docs/avaliacao/treino");

  private static final Pattern ACENTOS = Pattern.compile("\\p{M}");
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

  /** Sem acento, minúsculas, só letras e dígitos separados por um espaço. */
  static String normalizar(String texto) {
    String semAcento = ACENTOS.matcher(Normalizer.normalize(texto, Normalizer.Form.NFD))
        .replaceAll("").toLowerCase(Locale.ROOT);
    return NAO_ALFANUMERICO.matcher(semAcento).replaceAll(" ").trim();
  }

  private static String ler(Path arquivo) throws IOException {
    return new String(Files.readAllBytes(arquivo), StandardCharsets.UTF_8);
  }
}
