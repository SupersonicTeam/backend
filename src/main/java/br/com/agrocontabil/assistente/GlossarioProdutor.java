package br.com.agrocontabil.assistente;

import br.com.agrocontabil.common.NormalizacaoBusca;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Glossário do produtor (issue #267): siglas e termos que um produtor rural não precisa conhecer,
 * com a definição em linguagem dele. Fonte única, lida de {@code assistente/glossario-produtor.yml}:
 * a composição das respostas de dados insere a forma por extenso na primeira vez que a sigla
 * aparece, a medição de clareza e os testes dos textos fixos conferem "sigla sem explicação", e o
 * backend monta a seção/linha "O que isso quer dizer" com as definições (nunca o modelo).
 *
 * <p>A comparação com o texto ignora acento e caixa (a mesma normalização da busca): a explicação
 * já escrita por outro texto do backend conta, com ou sem acento. Função pura, sem Spring.
 */
public final class GlossarioProdutor {

  private static final String RECURSO = "/assistente/glossario-produtor.yml";

  private static final GlossarioProdutor PADRAO = carregarDoRecurso();

  private final List<Termo> termos;

  private GlossarioProdutor(List<Termo> termos) {
    this.termos = List.copyOf(termos);
  }

  /**
   * Um termo do glossário.
   *
   * @param termo      rótulo, como aparece no relatório e no começo da linha explicativa
   * @param sigla      {@code false} para termo que não é sigla ("crédito", "guia")
   * @param padrao     como o termo aparece no texto
   * @param nome       forma por extenso, ou nulo; vale como explicação se estiver no texto
   * @param aceitas    outras expressões que valem como explicação, já normalizadas (sem acento)
   * @param emLinha    se a composição de dados insere {@code (nome)} depois da primeira ocorrência
   * @param explicacao definição em linguagem de produtor, sem ponto final
   */
  public record Termo(String termo, boolean sigla, Pattern padrao, String nome,
      List<String> aceitas, boolean emLinha, String explicacao) {

    /** Cópia defensiva. */
    public Termo {
      aceitas = List.copyOf(aceitas);
    }

    /** O termo aparece no texto. */
    public boolean apareceEm(String texto) {
      return texto != null && padrao.matcher(texto).find();
    }

    /**
     * O texto já explica o termo: traz a forma por extenso, uma expressão aceita ou a própria
     * definição do glossário.
     *
     * @param normalizado texto já normalizado por {@link NormalizacaoBusca#normalizar}
     */
    public boolean explicadoEm(String normalizado) {
      if (nome != null && normalizado.contains(NormalizacaoBusca.normalizar(nome))) {
        return true;
      }
      return normalizado.contains(NormalizacaoBusca.normalizar(explicacao))
          || aceitas.stream().anyMatch(normalizado::contains);
    }
  }

  /** O glossário da aplicação, carregado uma vez do classpath. */
  public static GlossarioProdutor padrao() {
    return PADRAO;
  }

  /**
   * Lê um glossário de um YAML (testes e o padrão).
   *
   * @param yaml conteúdo
   * @return o glossário
   * @throws IllegalStateException se algum termo não tem rótulo, padrão válido ou definição
   */
  public static GlossarioProdutor carregar(InputStream yaml) {
    Object raiz = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
    Object lista = raiz instanceof Map<?, ?> m ? m.get("termos") : null;
    if (!(lista instanceof List<?> itens) || itens.isEmpty()) {
      throw new IllegalStateException("Glossario do produtor sem a lista 'termos'.");
    }
    List<Termo> termos = new ArrayList<>();
    for (Object item : itens) {
      termos.add(termo(item));
    }
    return new GlossarioProdutor(termos);
  }

  private static Termo termo(Object item) {
    if (!(item instanceof Map<?, ?> campos)) {
      throw new IllegalStateException("Termo do glossario do produtor invalido: " + item);
    }
    String termo = texto(campos.get("termo"));
    String padrao = texto(campos.get("padrao"));
    String explicacao = texto(campos.get("explicacao"));
    if (termo == null || padrao == null || explicacao == null) {
      throw new IllegalStateException("Termo do glossario do produtor sem termo, padrao ou"
          + " explicacao: " + (termo == null ? item : termo));
    }
    int opcoes = Boolean.TRUE.equals(campos.get("ignorarCaixa"))
        ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0;
    Pattern regex;
    try {
      regex = Pattern.compile(padrao, opcoes);
    } catch (PatternSyntaxException e) {
      throw new IllegalStateException("Padrao invalido no termo '" + termo + "' do glossario do"
          + " produtor.", e);
    }
    List<String> aceitas = new ArrayList<>();
    if (campos.get("aceitas") instanceof List<?> lista) {
      lista.forEach(a -> aceitas.add(NormalizacaoBusca.normalizar(String.valueOf(a))));
    }
    return new Termo(termo, !Boolean.FALSE.equals(campos.get("sigla")), regex,
        texto(campos.get("nome")), aceitas, Boolean.TRUE.equals(campos.get("emLinha")),
        explicacao);
  }

  private static String texto(Object valor) {
    return valor == null || String.valueOf(valor).isBlank() ? null : String.valueOf(valor).strip();
  }

  private static GlossarioProdutor carregarDoRecurso() {
    try (InputStream in = GlossarioProdutor.class.getResourceAsStream(RECURSO)) {
      if (in == null) {
        throw new IllegalStateException("Recurso " + RECURSO + " ausente.");
      }
      return carregar(in);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Todos os termos, na ordem do arquivo. */
  public List<Termo> termos() {
    return termos;
  }

  /**
   * Termos que aparecem no texto e não estão explicados nele, na ordem do glossário.
   *
   * @param texto texto entregue ao produtor; nulo dá lista vazia
   * @return termos sem explicação
   */
  public List<Termo> semExplicacao(String texto) {
    if (texto == null || texto.isBlank()) {
      return List.of();
    }
    String normalizado = NormalizacaoBusca.normalizar(texto);
    return termos.stream()
        .filter(t -> t.apareceEm(texto) && !t.explicadoEm(normalizado))
        .toList();
  }

  /** Como {@link #semExplicacao(String)}, só para siglas (os termos comuns não entram). */
  public List<Termo> siglasSemExplicacao(String texto) {
    return semExplicacao(texto).stream().filter(Termo::sigla).toList();
  }

  /**
   * O que explicar para o texto ficar sem sigla solta: os termos sem explicação e, em seguida, os
   * que as definições deles citam (a do IBS fala do ICMS e do ISS), até não haver mais nenhum.
   * Cada termo vem uma vez, na ordem em que foi achado.
   *
   * @param texto texto entregue ao produtor; nulo dá lista vazia
   * @return termos a explicar, com a definição de cada um
   */
  public List<Termo> aExplicar(String texto) {
    List<Termo> aExplicar = new ArrayList<>(semExplicacao(texto));
    String normalizado = NormalizacaoBusca.normalizar(texto);
    for (int i = 0; i < aExplicar.size(); i++) {
      String definicao = aExplicar.get(i).explicacao();
      for (Termo t : termos) {
        if (!aExplicar.contains(t) && t.apareceEm(definicao) && !t.explicadoEm(normalizado)) {
          aExplicar.add(t);
        }
      }
    }
    return List.copyOf(aExplicar);
  }
}
