package br.com.agrocontabil.support;

import java.util.ArrayList;
import java.util.List;

/**
 * Lê os literais de string de um arquivo {@code .java}, com a chamada que os envolve (issue #476).
 *
 * <p>Não é um parser de Java: separa comentário, literal de caractere, literal de string e bloco de
 * texto, o bastante para as guardas que varrem o código-fonte. O <em>chamador</em> é o nome antes
 * do parêntese aberto mais próximo que envolve o literal ({@code ErrorDetail.of},
 * {@code log.warn}, {@code @NotBlank}, {@code super}); no nível da instrução, é {@code "="} seguido
 * do começo da instrução ({@code "=private static final String MENSAGEM ="}).
 */
public final class LiteraisJava {

  /**
   * Um literal de string.
   *
   * @param texto    conteúdo, sem as aspas (escapes como estão no fonte)
   * @param linha    linha em que o literal começa (1 = primeira)
   * @param chamador chamada ou instrução que envolve o literal
   */
  public record Literal(String texto, int linha, String chamador) {}

  private LiteraisJava() {
  }

  /**
   * Os literais de string do fonte, na ordem em que aparecem.
   *
   * @param fonte código-fonte Java
   * @return literais com linha e chamador
   */
  public static List<Literal> de(String fonte) {
    char[] mascarado = fonte.toCharArray();
    List<int[]> literais = new ArrayList<>();
    int i = 0;
    int n = fonte.length();
    while (i < n) {
      int fim;
      if (fonte.startsWith("//", i)) {
        fim = fonte.indexOf('\n', i);
        fim = fim < 0 ? n : fim;
        mascarar(mascarado, i, fim, ' ');
      } else if (fonte.startsWith("/*", i)) {
        fim = fonte.indexOf("*/", i + 2);
        fim = fim < 0 ? n : fim + 2;
        mascarar(mascarado, i, fim, ' ');
      } else if (fonte.startsWith("\"\"\"", i)) {
        fim = fonte.indexOf("\"\"\"", i + 3) + 3;
        literais.add(new int[] {i, fim, 3});
        mascarar(mascarado, i, fim, 'x');
      } else if (fonte.charAt(i) == '"' || fonte.charAt(i) == '\'') {
        char aspa = fonte.charAt(i);
        int j = i + 1;
        while (fonte.charAt(j) != aspa) {
          j += fonte.charAt(j) == '\\' ? 2 : 1;
        }
        fim = j + 1;
        if (aspa == '"') {
          literais.add(new int[] {i, fim, 1});
        }
        mascarar(mascarado, i, fim, 'x');
      } else {
        fim = i + 1;
      }
      i = fim;
    }
    List<Literal> resultado = new ArrayList<>();
    int linha = 1;
    int contadoAte = 0;
    for (int[] l : literais) {
      for (; contadoAte < l[0]; contadoAte++) {
        if (fonte.charAt(contadoAte) == '\n') {
          linha++;
        }
      }
      String texto = fonte.substring(l[0] + l[2], l[1] - l[2]);
      resultado.add(new Literal(texto, linha, chamador(mascarado, l[0])));
    }
    return resultado;
  }

  private static void mascarar(char[] fonte, int inicio, int fim, char troca) {
    for (int k = inicio; k < fim; k++) {
      if (fonte[k] != '\n') {
        fonte[k] = troca;
      }
    }
  }

  private static String chamador(char[] fonte, int posicao) {
    int nivel = 0;
    for (int j = posicao - 1; j >= 0; j--) {
      char c = fonte[j];
      if (c == ')') {
        nivel++;
      } else if (c == '(') {
        if (nivel == 0) {
          int k = j - 1;
          while (k >= 0 && Character.isWhitespace(fonte[k])) {
            k--;
          }
          int fim = k + 1;
          while (k >= 0 && (Character.isLetterOrDigit(fonte[k]) || "_.@".indexOf(fonte[k]) >= 0)) {
            k--;
          }
          return fim > k + 1 ? new String(fonte, k + 1, fim - k - 1) : "(";
        }
        nivel--;
      } else if ((c == ';' || c == '{' || c == '}') && nivel == 0) {
        return "=" + new String(fonte, j + 1, posicao - j - 1).strip().replaceAll("\\s+", " ");
      }
    }
    return "?";
  }
}
