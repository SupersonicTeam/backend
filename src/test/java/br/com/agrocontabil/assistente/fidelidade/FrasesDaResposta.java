package br.com.agrocontabil.assistente.fidelidade;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Monta a entrada do juiz (#269) a partir da resposta final da Ceres, como o
 * {@code POST /api/assistente/chat} a devolve: texto com as referências entre parênteses
 * ("(LC 214/2025, art. 138)") e a lista de citações. Usada pela {@link BancadaJuizFidelidadeManual}.
 *
 * <p>Regras, na ordem:
 * <ol>
 *   <li>a linha do glossário ("O que isso quer dizer:", montada pelo backend) sai;</li>
 *   <li>cada parêntese cujas partes (separadas por "; ") são todas referências de citações da
 *       lista ({@code norma + ", " + dispositivo}) vira a marca dessas citações e sai do texto;</li>
 *   <li>o texto é dividido em frases no ponto final, de exclamação ou de interrogação seguido de
 *       espaço e letra maiúscula, e em cada quebra de linha;</li>
 *   <li>frase sem referência própria usa as citações da próxima frase com referência (o modelo pode
 *       marcar só o fim de um grupo de frases) ou, sem próxima, as da anterior; sem nenhuma, a frase
 *       fica de fora e é contada;</li>
 *   <li>frase sem palavra de conteúdo ("Sim.", "É de 60%.") fica de fora, como na camada 1, mas
 *       a referência dela vale para as vizinhas;</li>
 *   <li>as passagens de cada frase são os textos dos dispositivos citados, pelo mapa dado (o banco
 *       de lei); dispositivo que ela não acha usa o {@code trecho} da própria citação e é contado.</li>
 * </ol>
 */
final class FrasesDaResposta {

  private static final String TITULO_GLOSSARIO = "O que isso quer dizer:";
  private static final Pattern PARENTESE = Pattern.compile("\\s*\\(([^()]+)\\)");
  private static final Pattern MARCA = Pattern.compile("\u0001(\\d+(?:,\\d+)*)\u0001");
  private static final Pattern QUEBRA =
      Pattern.compile("(?<=[.!?])\\s+(?=[\\p{Lu}\"“])|\\s*\\n+\\s*");

  private FrasesDaResposta() {}

  /**
   * Citação como o chat a devolve.
   *
   * @param norma       ex.: {@code LC 214/2025}
   * @param dispositivo ex.: {@code art. 138, § 1º}
   * @param trecho      texto do dispositivo na resposta (usado se o banco não o tiver)
   */
  record Citacao(String norma, String dispositivo, String trecho) {

    String referencia() {
      return norma + ", " + dispositivo;
    }

    /** A chave da busca de dispositivos ({@code norma|dispositivo}). */
    String chave() {
      return norma + "|" + dispositivo;
    }
  }

  /**
   * Resultado da montagem.
   *
   * @param frases              as frases com as passagens, na ordem, para o juiz
   * @param semCitacao          frases de conteúdo que ficaram de fora por não ter citação nenhuma
   * @param dispositivosAusentes chaves que o banco não achou (a passagem veio da citação)
   */
  record Montagem(List<ConferenciaFidelidadeLei.FraseConferida> frases, int semCitacao,
      List<String> dispositivosAusentes) {}

  /**
   * Monta as frases do juiz.
   *
   * @param resposta  texto final da resposta
   * @param citacoes  citações da resposta, na ordem devolvida
   * @param porChave  textos dos dispositivos achados no banco, pela {@link Citacao#chave()}
   * @return a montagem
   */
  static Montagem montar(String resposta, List<Citacao> citacoes, Map<String, String> porChave) {
    String texto = resposta == null ? "" : resposta;
    int glossario = texto.indexOf(TITULO_GLOSSARIO);
    if (glossario >= 0) {
      texto = texto.substring(0, glossario);
    }
    texto = marcar(texto, citacoes);

    List<String> textos = new ArrayList<>();
    List<List<Integer>> marcas = new ArrayList<>();
    List<Boolean> comConteudo = new ArrayList<>();
    for (String pedaco : QUEBRA.split(texto.strip())) {
      List<Integer> indices = new ArrayList<>();
      Matcher m = MARCA.matcher(pedaco);
      while (m.find()) {
        for (String i : m.group(1).split(",")) {
          indices.add(Integer.parseInt(i));
        }
      }
      String frase = MARCA.matcher(pedaco).replaceAll("").replaceAll("\\s+([.,;:!?])", "$1")
          .replaceAll("\\s+", " ").strip();
      // A frase sem conteudo nao vai ao juiz, mas a referencia dela vale para as vizinhas.
      textos.add(frase);
      marcas.add(indices);
      comConteudo.add(TextoLei.formas(frase).stream().anyMatch(TextoLei::conteudo));
    }

    Set<String> ausentes = new LinkedHashSet<>();
    Function<Citacao, String> passagem = c -> {
      String doBanco = porChave.get(c.chave());
      if (doBanco != null) {
        return doBanco;
      }
      ausentes.add(c.chave());
      return c.trecho() == null ? "" : c.trecho();
    };
    List<ConferenciaFidelidadeLei.FraseConferida> frases = new ArrayList<>();
    int semCitacao = 0;
    for (int i = 0; i < textos.size(); i++) {
      if (!comConteudo.get(i)) {
        continue;
      }
      List<Integer> indices = emprestadas(marcas, i);
      if (indices.isEmpty()) {
        semCitacao++;
        continue;
      }
      Set<String> passagens = new LinkedHashSet<>();
      indices.forEach(j -> passagens.add(passagem.apply(citacoes.get(j))));
      passagens.remove("");
      if (passagens.isEmpty()) {
        semCitacao++;
        continue;
      }
      frases.add(new ConferenciaFidelidadeLei.FraseConferida(textos.get(i), List.copyOf(passagens)));
    }
    return new Montagem(frases, semCitacao, List.copyOf(ausentes));
  }

  /** As próprias marcas da frase; sem elas, as da próxima frase marcada; sem próxima, da anterior. */
  private static List<Integer> emprestadas(List<List<Integer>> marcas, int i) {
    if (!marcas.get(i).isEmpty()) {
      return marcas.get(i);
    }
    for (int j = i + 1; j < marcas.size(); j++) {
      if (!marcas.get(j).isEmpty()) {
        return marcas.get(j);
      }
    }
    for (int j = i - 1; j >= 0; j--) {
      if (!marcas.get(j).isEmpty()) {
        return marcas.get(j);
      }
    }
    return List.of();
  }

  /** Troca cada parêntese de referências pela marca dos índices das citações. */
  private static String marcar(String texto, List<Citacao> citacoes) {
    Matcher m = PARENTESE.matcher(texto);
    StringBuilder saida = new StringBuilder();
    while (m.find()) {
      List<String> indices = new ArrayList<>();
      for (String parte : m.group(1).split(";")) {
        int indice = -1;
        for (int i = 0; i < citacoes.size(); i++) {
          if (citacoes.get(i).referencia().equals(parte.strip())) {
            indice = i;
            break;
          }
        }
        if (indice < 0) {
          indices = null;
          break;
        }
        indices.add(String.valueOf(indice));
      }
      m.appendReplacement(saida, indices == null
          ? Matcher.quoteReplacement(m.group())
          : Matcher.quoteReplacement(" \u0001" + String.join(",", indices) + "\u0001"));
    }
    m.appendTail(saida);
    return saida.toString();
  }
}
