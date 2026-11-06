package br.com.agrocontabil.assistente;

import java.util.ArrayList;
import java.util.List;

/**
 * Linha "O que isso quer dizer" ao fim da resposta de lei (issue #269): a definição, em linguagem de
 * produtor, de cada termo do {@link GlossarioProdutor} que a resposta usa sem explicar.
 *
 * <p>Montada pelo backend depois de todas as conferências (citação, números, afirmação sobre dados),
 * nunca pelo modelo: a definição vem do glossário revisado por humano, sem número nem artigo, então
 * a linha não vira fonte de número nem muda a lista de citações. Função pura, sem Spring.
 */
final class LinhaGlossario {

  /** Título da linha. */
  static final String TITULO = "O que isso quer dizer:";

  private LinhaGlossario() {}

  /**
   * A linha para o texto, ou vazio se ele não deixa termo do glossário sem explicação.
   *
   * @param texto resposta já validada, com as referências legais montadas
   * @return o título e um item por termo ({@code "- IBS: definição."}), ou {@code ""}
   */
  static String para(String texto) {
    List<GlossarioProdutor.Termo> termos = GlossarioProdutor.padrao().aExplicar(texto);
    if (termos.isEmpty()) {
      return "";
    }
    List<String> linhas = new ArrayList<>();
    linhas.add(TITULO);
    termos.forEach(t -> linhas.add("- " + t.termo() + ": " + t.explicacao() + "."));
    return String.join("\n", linhas);
  }

  /** O texto seguido da linha, em outro parágrafo; o próprio texto se não há o que explicar. */
  static String acrescentadaA(String texto) {
    String linha = para(texto);
    return linha.isEmpty() ? texto : texto + "\n\n" + linha;
  }
}
