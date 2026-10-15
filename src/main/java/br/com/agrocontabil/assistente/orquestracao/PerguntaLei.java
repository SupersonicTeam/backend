package br.com.agrocontabil.assistente.orquestracao;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pergunta que a parte de lei do MISTO recebe.
 *
 * <p>Com a pergunta inteira, a busca de trechos fica diluída pelo pedido de dados ("quanto comprei
 * de adubo e tenho crédito?") e o modelo da regra geral, proibido de falar dos dados, recebe um
 * pedido de dados. Por isso o classificador separa a parte legal ({@code perguntaLei}). Mas é
 * texto gerado pelo modelo, então só vale se:
 * <ul>
 *   <li>não estiver em branco e couber em {@link #TAMANHO_MAXIMO} caracteres;</li>
 *   <li>não tiver forma de prompt injection ({@link FiltroInjecao});</li>
 *   <li>não trouxer número que o usuário não escreveu. O {@code ValidadorNumeros} aceita na
 *       resposta os números da pergunta: um "redução de 60%" inventado na reescrita passaria a
 *       valer como fonte.</li>
 * </ul>
 * Fora disso, vale a pergunta original.
 *
 * @param texto     pergunta a usar na parte de lei
 * @param reescrita {@code true} se é a pergunta separada pelo classificador
 */
record PerguntaLei(String texto, boolean reescrita) {

  /** Teto da pergunta reescrita: a parte legal de uma pergunta de até 1000 caracteres. */
  static final int TAMANHO_MAXIMO = 300;

  private static final Pattern DIGITOS = Pattern.compile("\\d+");

  /**
   * Escolhe a pergunta da parte de lei.
   *
   * @param original  pergunta do usuário
   * @param reescrita parte legal separada pelo classificador, ou nula
   * @return a reescrita, se passou nas conferências; senão a original
   */
  static PerguntaLei escolher(String original, String reescrita) {
    if (reescrita == null || reescrita.isBlank() || reescrita.length() > TAMANHO_MAXIMO
        || FiltroInjecao.pareceInjecao(reescrita)
        || !digitos(original).containsAll(digitos(reescrita))) {
      return new PerguntaLei(original, false);
    }
    return new PerguntaLei(reescrita.strip(), true);
  }

  private static Set<String> digitos(String texto) {
    Set<String> encontrados = new HashSet<>();
    Matcher m = DIGITOS.matcher(texto == null ? "" : texto);
    while (m.find()) {
      encontrados.add(m.group());
    }
    return encontrados;
  }
}
