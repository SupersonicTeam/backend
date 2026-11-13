package br.com.agrocontabil.assistente.fidelidade;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Níveis de um dispositivo dentro do texto do trecho (#269): o {@code FatiadorDispositivos} grava o
 * parágrafo com o caput na frente, o inciso com o caput e o parágrafo, a alínea com os três. Pela
 * posição de uma passagem nesse texto dá para saber em qual nível ela está escrita, e a citação
 * pode apontar o nível certo (o caput, quando a regra está no caput).
 *
 * <p>Só a forma simples do rótulo ({@code art. 408, § 4º, inciso II, alínea b}); redação dada por
 * emenda, item de anexo e rótulo repetido ({@code [2]}) ficam de fora ({@link #de} devolve nulo), e
 * a citação deles não muda. Função pura, sem Spring.
 */
final class NivelDispositivo {

  private static final Pattern ROTULO = Pattern.compile(
      "^(art\\. \\d+º?(?:-[A-Z])?)"
          + "(?:, (§ (\\d+)º?(?:-([A-Z]))?|parágrafo único))?"
          + "(?:, (inciso ([IVXLCDM]+)(?:-([A-Z]))?))?"
          + "(?:, (alínea ([a-z])))?$");

  private final List<String> rotulos;
  private final List<Integer> inicios;
  private final String texto;

  private NivelDispositivo(List<String> rotulos, List<Integer> inicios, String texto) {
    this.rotulos = rotulos;
    this.inicios = inicios;
    this.texto = texto;
  }

  /**
   * Um ancestral do dispositivo: rótulo e texto (o começo do texto do trecho, até onde começa o
   * nível seguinte).
   *
   * @param dispositivo rótulo, ex.: {@code art. 408, § 4º}
   * @param texto       texto do ancestral como o fatiador o gravaria
   */
  record Ancestral(String dispositivo, String texto) {}

  /**
   * Os níveis do dispositivo no texto.
   *
   * @param dispositivo rótulo do trecho
   * @param texto       texto do trecho
   * @return os níveis; nulo se o rótulo não tem a forma simples ou se o texto não traz o começo de
   *         algum nível (nesse caso a citação não muda)
   */
  static NivelDispositivo de(String dispositivo, String texto) {
    if (dispositivo == null || texto == null) {
      return null;
    }
    Matcher m = ROTULO.matcher(dispositivo);
    if (!m.matches()) {
      return null;
    }
    List<String> rotulos = new ArrayList<>();
    List<Pattern> marcadores = new ArrayList<>();
    String acumulado = m.group(1);
    rotulos.add(acumulado);
    if (m.group(2) != null) {
      acumulado += ", " + m.group(2);
      rotulos.add(acumulado);
      marcadores.add(m.group(3) == null
          ? Pattern.compile("Par[áa]grafo\\s+[úu]nico", Pattern.CASE_INSENSITIVE)
          : Pattern.compile("§\\s*" + m.group(3) + "(?!\\d)"));
    }
    if (m.group(5) != null) {
      acumulado += ", " + m.group(5);
      rotulos.add(acumulado);
      String sufixo = m.group(7) == null ? "" : "\\s*-\\s*" + m.group(7);
      marcadores.add(Pattern.compile(
          "(?<![\\p{L}\\p{N}])" + m.group(6) + sufixo + "\\s*[-–—]"));
    }
    if (m.group(8) != null) {
      acumulado += ", " + m.group(8);
      rotulos.add(acumulado);
      marcadores.add(Pattern.compile("(?<![\\p{L}\\p{N}])" + m.group(9) + "\\)"));
    }
    // Do nivel mais fundo para o caput: cada um comeca na ultima ocorrencia do marcador antes do
    // comeco do nivel seguinte (o texto proprio do nivel vem sempre depois do de quem o contem). O
    // marcador so vale logo depois do fim do nivel de cima (".", ":" ou ";" e espaco), como o
    // fatiador os junta: uma remissao no meio do texto ("conforme o § 4º") nao e comeco de nivel.
    List<Integer> inicios = new ArrayList<>();
    int limite = texto.length();
    for (int i = marcadores.size() - 1; i >= 0; i--) {
      int inicio = ultimo(
          Pattern.compile("(?<=[.:;])\\s+(" + marcadores.get(i).pattern() + ")",
              marcadores.get(i).flags()),
          texto, limite);
      if (inicio <= 0) {
        return null;
      }
      inicios.add(0, inicio);
      limite = inicio;
    }
    inicios.add(0, 0);
    return new NivelDispositivo(List.copyOf(rotulos), List.copyOf(inicios), texto);
  }

  private static int ultimo(Pattern marcador, String texto, int limite) {
    Matcher m = marcador.matcher(texto);
    m.region(0, limite);
    int ultimo = -1;
    while (m.find()) {
      ultimo = m.start(1);
    }
    return ultimo;
  }

  /**
   * O texto próprio dos níveis que a passagem toca, do começo do nível em que ela começa ao fim do
   * nível em que ela termina (#269): é o que o dispositivo citado diz, e a frase pode resumi-lo
   * todo, mesmo que a cópia seja só um pedaço dele. O texto dos níveis de cima (o caput, para uma
   * cópia no inciso) não entra.
   *
   * @param inicio  posição da primeira palavra da passagem no texto do trecho
   * @param posicao posição da última palavra
   * @return o texto dos níveis tocados
   */
  String textoDosNiveis(int inicio, int posicao) {
    int primeiro = nivelDe(inicio);
    int ultimo = nivelDe(posicao);
    int fim = ultimo + 1 < inicios.size() ? inicios.get(ultimo + 1) : texto.length();
    return texto.substring(inicios.get(primeiro), fim).strip();
  }

  /**
   * O texto do trecho do começo (o caput) até o fim do nível em que a passagem termina (#269): o
   * dispositivo citado com o contexto dos níveis de cima ("Durante o período de 2029 a 2032:" diz
   * de quando vale a alínea). Serve ao número e à cobertura; a negação da frase confere só o texto
   * próprio ({@link #textoDosNiveis}), para o "não" de uma condição de cima não valer pela frase.
   *
   * @param posicao posição da última palavra da passagem
   * @return o texto do caput ao fim do nível
   */
  String textoAte(int posicao) {
    int ultimo = nivelDe(posicao);
    int fim = ultimo + 1 < inicios.size() ? inicios.get(ultimo + 1) : texto.length();
    return texto.substring(0, fim).strip();
  }

  private int nivelDe(int posicao) {
    int nivel = 0;
    for (int i = 0; i < inicios.size(); i++) {
      if (inicios.get(i) <= posicao) {
        nivel = i;
      }
    }
    return nivel;
  }

  /**
   * O ancestral em que está escrita a passagem que vai até {@code posicao}: o nível mais fundo que
   * começa antes dela.
   *
   * @param posicao posição, no texto do trecho, da última palavra da passagem
   * @return o ancestral; nulo se a passagem já está no nível do próprio trecho
   */
  Ancestral ancestralDe(int posicao) {
    int nivel = nivelDe(posicao);
    if (nivel == rotulos.size() - 1) {
      return null;
    }
    return new Ancestral(rotulos.get(nivel), texto.substring(0, inicios.get(nivel + 1)).strip());
  }
}
