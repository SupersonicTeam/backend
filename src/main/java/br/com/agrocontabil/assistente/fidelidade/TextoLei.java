package br.com.agrocontabil.assistente.fidelidade;

import br.com.agrocontabil.common.NormalizacaoBusca;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Régua lexical da conferência de fidelidade (#269): palavras com a posição no texto original,
 * comparadas sem acento nem caixa, e as listas de palavras vazias, de negação e de restrição.
 * Função pura, sem Spring.
 */
final class TextoLei {

  /**
   * Palavra ou número; hífen e pontuação separam ("ano-calendário" = duas palavras). O indicador
   * ordinal (º, ª) é letra para o Unicode, mas aqui separa: "1º" é o número 1, como "1°" ou "1o"
   * escrito pelo modelo, e entra na conferência de número.
   */
  private static final Pattern PALAVRA = Pattern.compile("[\\p{L}\\p{N}&&[^ºª]]+");
  private static final Pattern SO_DIGITOS = Pattern.compile("\\d+");

  /** Tamanho da raiz: "cobrança" e "cobrar" casam, "estadual" e "estados" também. */
  private static final int RAIZ = 5;

  /** Palavra com menos letras que isto não conta na cobertura (artigos, preposições). */
  private static final int MINIMO_CONTEUDO = 3;

  /**
   * Negação estrita: se a frase tem uma, a evidência precisa de uma {@link #NEGACAO_AMPLA}; se a
   * evidência tem uma, a frase precisa de uma {@link #NEGACAO_AMPLA}.
   */
  static final Set<String> NEGACAO = Set.of(
      "nao", "nem", "nenhum", "nenhuma", "nunca", "jamais", "vedado", "vedada", "vedados",
      "vedadas", "vedacao", "proibido", "proibida", "exceto", "excetuado", "excetuados",
      "excetuada", "excetuadas", "salvo", "ressalvado", "ressalvada", "ressalvados", "ressalvadas",
      "excluido", "excluida", "excluidos", "excluidas");

  /**
   * Formas que dizem "não" sem a palavra: isenção, imunidade, alíquota zero, suspensão. Valem como
   * resposta à negação estrita do outro lado, mas sozinhas não a exigem ("sem prejuízo" é comum).
   */
  static final Set<String> NEGACAO_AMPLA = uniao(NEGACAO, Set.of(
      "sem", "isento", "isenta", "isentos", "isentas", "isencao", "imune", "imunes", "imunidade",
      "suspensao", "suspenso", "suspensa", "suspensos", "suspensas", "dispensa", "dispensado",
      "dispensada", "zero", "zerada", "zeradas", "zerado", "fora", "deixa", "deixam", "afastado",
      "afastada", "inexigivel"));

  /** Restrição: se a frase restringe ("só o IBS"), a evidência também tem de restringir. */
  static final Set<String> RESTRICAO = Set.of(
      "so", "somente", "apenas", "exclusivamente", "exclusivo", "exclusiva", "exclusivos",
      "exclusivas", "unicamente");

  /**
   * Palavras que não afirmam nada sobre a regra: artigos, preposições, verbos de ligação e as que
   * só apontam para a lei ("artigo", "prevê", "estabelece").
   */
  static final Set<String> VAZIAS = Set.of(
      "a", "o", "as", "os", "um", "uma", "uns", "umas", "de", "do", "da", "dos", "das", "em", "no",
      "na", "nos", "nas", "num", "numa", "por", "pelo", "pela", "pelos", "pelas", "para", "pra",
      "com", "e", "ou", "que", "se", "ao", "aos", "ser", "sera", "serao", "seria", "sao", "foi",
      "era", "esta", "estao", "este", "estes", "estas", "isso", "isto", "essa", "esse", "esses",
      "essas", "nesse", "nessa", "neste", "nesta", "ele", "ela", "eles", "elas", "seu", "sua",
      "seus", "suas", "lhe", "como", "mais", "mas", "tambem", "ja", "quando", "onde", "qual",
      "quais", "quem", "cujo", "cuja", "ha", "ter", "tem", "tenha", "tiver", "tiverem", "pode",
      "podem", "poder", "deve", "devem", "dever", "sobre", "entre", "ate", "apos", "desde", "cada",
      "outro", "outra", "outros", "outras", "mesmo", "mesma", "sim", "assim", "entao", "porque",
      "pois", "muito", "ainda", "caso", "casos", "vale", "valem", "valer", "fica", "ficam", "ficar",
      "lei", "leis", "regra", "regras", "trecho", "trechos", "legislacao", "preve", "prevista",
      "previsto", "estabelece", "estabelecem", "determina", "determinam", "dispoe", "diz", "dizem",
      "define", "definem", "conforme", "termos", "forma", "artigo", "artigos", "paragrafo",
      "inciso", "alinea", "complementar", "anexo", "seja", "sejam", "for", "forem", "durante",
      "voce", "aqui", "tal", "tais", "qualquer", "todo", "toda", "todos", "todas", "quanto",
      "seguinte", "seguintes", "existe", "existem", "significa", "quer", "dizer", "nela",
      "nele", "dele", "dela", "deles", "delas", "sendo", "sido", "estar", "hipotese", "hipoteses");

  /**
   * Palavra do texto: a forma comparável (sem acento, minúscula) e onde ela está no original.
   *
   * @param forma  palavra normalizada
   * @param inicio posição do primeiro caractere no texto original
   */
  record Palavra(String forma, int inicio) {}

  private TextoLei() {}

  private static Set<String> uniao(Set<String> a, Set<String> b) {
    java.util.HashSet<String> u = new java.util.HashSet<>(a);
    u.addAll(b);
    return Set.copyOf(u);
  }

  /** As palavras do texto, na ordem, com a posição no original. */
  static List<Palavra> palavras(String texto) {
    List<Palavra> lista = new ArrayList<>();
    if (texto == null) {
      return lista;
    }
    Matcher m = PALAVRA.matcher(texto);
    while (m.find()) {
      lista.add(new Palavra(normalizar(m.group()), m.start()));
    }
    return lista;
  }

  /** Só as formas, na ordem. */
  static List<String> formas(String texto) {
    return palavras(texto).stream().map(Palavra::forma).toList();
  }

  /** Sem acento e em minúsculas, pela regra única da busca ({@link NormalizacaoBusca}). */
  static String normalizar(String palavra) {
    return NormalizacaoBusca.normalizar(palavra);
  }

  /**
   * Raiz de comparação: os primeiros {@value #RAIZ} caracteres, com "z" lido como "c" ("redução" e
   * "reduzidas" casam; o "ç" já virou "c" na normalização).
   */
  static String raiz(String forma) {
    String raiz = forma.length() > RAIZ ? forma.substring(0, RAIZ) : forma;
    return raiz.replace('z', 'c');
  }

  /** {@code true} se a forma é só dígitos. */
  static boolean numero(String forma) {
    return SO_DIGITOS.matcher(forma).matches();
  }

  /**
   * {@code true} se a palavra afirma algo sobre a regra: não é vazia, não é número, não é negação
   * nem restrição (conferidas à parte) e tem ao menos {@value #MINIMO_CONTEUDO} letras.
   */
  static boolean conteudo(String forma) {
    return forma.length() >= MINIMO_CONTEUDO && !numero(forma) && !VAZIAS.contains(forma)
        && !NEGACAO_AMPLA.contains(forma) && !RESTRICAO.contains(forma);
  }
}
