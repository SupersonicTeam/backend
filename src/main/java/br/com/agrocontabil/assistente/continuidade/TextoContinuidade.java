package br.com.agrocontabil.assistente.continuidade;

import br.com.agrocontabil.assistente.orquestracao.RegrasClassificacao;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalização e listas de palavras da continuidade (#499), sobre o
 * {@link RegrasClassificacao#normalizar} (sem acento, caixa e pontuação) e sem o vocativo
 * "ceres".
 */
final class TextoContinuidade {

  private static final Pattern VOCATIVO = Pattern.compile("\\bceres\\b");
  private static final Pattern NUMERO = Pattern.compile("\\d+(?:[.,]\\d+)*");

  /** Palavras vazias: artigos, preposições, pronomes e verbos de ligação. */
  static final Set<String> VAZIAS = Set.of("a", "o", "as", "os", "um", "uma", "uns", "umas", "de",
      "da", "do", "das", "dos", "em", "na", "no", "nas", "nos", "e", "que", "eu", "me", "mim",
      "meu", "minha", "meus", "minhas", "isso", "isto", "se", "pra", "para", "por", "pelo",
      "pela", "pelos", "pelas", "com", "sem", "mas", "ou", "ao", "aos", "foi", "era", "sou",
      "esta", "estava", "estao", "ser", "ter", "tinha", "tenho", "voce", "vc", "ne", "aqui", "ja",
      "so", "la", "ai", "lhe", "te", "nem", "tao", "muito", "bem", "ate", "seu", "sua", "seus",
      "suas", "nao", "sim", "agora", "entao", "tambem", "ainda", "sobre", "qual", "quais",
      "quanto", "quantos", "quanta", "quantas", "como", "onde", "quando", "porque", "por que",
      "o que", "quem", "qualquer", "algum", "alguma", "alguns", "algumas", "todo", "toda",
      "todos", "todas");

  /** Conectivos do começo da mensagem (gatilho a). */
  static final Set<String> CONECTIVOS = Set.of("e", "mas", "entao", "tambem");

  /** Anáforas (gatilho b). */
  static final Set<String> ANAFORAS = Set.of("isso", "disso", "nisso", "desse", "dessa",
      "desses", "dessas", "nesse", "nessa", "dele", "dela", "deles", "delas", "nele", "nela",
      "mesmo", "mesma");

  /** Demonstrativos (gatilho b). */
  static final Set<String> DEMONSTRATIVOS = Set.of("esse", "essa", "esses", "essas", "este",
      "esta", "estes", "estas", "aquele", "aquela");

  /** Interrogativos. */
  static final Set<String> INTERROGATIVOS = Set.of("qual", "quais", "quanto", "quantos",
      "quanta", "quantas", "como", "onde", "quando", "porque", "quem", "que");

  /** Palavras do SEGUIMENTO (gatilho d). */
  static final Set<String> SEGUIMENTO = Set.of("por", "porque", "como", "assim", "explica",
      "explique", "melhor", "novo", "entendi", "entao", "agora", "certeza", "serio", "repete",
      "repita", "confere", "confira", "confirma", "confirme", "verifica", "verifique");

  /** Marcadores de contestação, palavra por palavra. */
  static final Set<String> MARCADORES = Set.of("errado", "errada", "errou", "incorreto",
      "incorreta", "certo", "bate", "confere", "foi", "era");

  /** Verbos de pedido e de fala. */
  private static final Pattern VERBOS = Pattern.compile(
      "^(pergunt\\w*|quer\\w*|quis|falei|disse|falou|queria)$");

  /** Ordinais (gatilho c). */
  private static final Pattern ORDINAL = Pattern.compile(
      "^(primeir|segund|terceir|quart|quint|ultim|penultim)(a|o|as|os)$");

  private TextoContinuidade() {
  }

  /** Normalizada, sem vocativo e com espaços simples. */
  static String normalizar(String texto) {
    if (texto == null) {
      return "";
    }
    String normal = RegrasClassificacao.normalizar(texto);
    return VOCATIVO.matcher(normal).replaceAll(" ").replaceAll("\\s+", " ").strip();
  }

  /** As palavras da forma normalizada. */
  static List<String> palavras(String normalizada) {
    return normalizada.isBlank() ? List.of() : Arrays.asList(normalizada.split(" "));
  }

  /** Prefixo de comparação: até 5 letras. */
  static String prefixo(String palavra) {
    return palavra.length() <= 5 ? palavra : palavra.substring(0, 5);
  }

  /** {@code true} se alguma palavra do texto tem o mesmo prefixo de 5 letras. */
  static boolean contemPorPrefixo(List<String> palavrasTexto, String palavra) {
    String p = prefixo(palavra);
    return palavrasTexto.stream().anyMatch(w -> prefixo(w).equals(p));
  }

  /** {@code true} se a palavra é número. */
  static boolean numero(String palavra) {
    return palavra.chars().anyMatch(Character::isDigit);
  }

  /** Os números do texto cru, sem a pontuação interna. */
  static List<String> numeros(String texto) {
    List<String> numeros = new ArrayList<>();
    Matcher m = NUMERO.matcher(texto == null ? "" : texto);
    while (m.find()) {
      numeros.add(m.group().replaceAll("[.,]", ""));
    }
    return numeros;
  }

  /** Verbo de pedido ou de fala. */
  static boolean verboDePedido(String palavra) {
    return VERBOS.matcher(palavra).matches();
  }

  /** Ordinal. */
  static boolean ordinal(String palavra) {
    return ORDINAL.matcher(palavra).matches();
  }

  /** Palavra de conteúdo: 4 letras ou mais e fora das listas de função. */
  static boolean conteudo(String palavra) {
    return palavra.length() >= 4 && !VAZIAS.contains(palavra) && !CONECTIVOS.contains(palavra)
        && !ANAFORAS.contains(palavra) && !DEMONSTRATIVOS.contains(palavra)
        && !INTERROGATIVOS.contains(palavra) && !SEGUIMENTO.contains(palavra)
        && !MARCADORES.contains(palavra) && !verboDePedido(palavra) && !ordinal(palavra);
  }
}
