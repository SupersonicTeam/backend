package br.com.agrocontabil.assistente.rag;

import br.com.agrocontabil.common.NormalizacaoBusca;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Expansão determinística da consulta da busca de lei com o vocabulário da própria lei (#269,
 * experimento). A pergunta do produtor raramente usa as palavras do dispositivo; o tema da regra
 * geral casado na pergunta acrescenta, <strong>só à consulta de similaridade</strong>, a frase da
 * lei que o trata. O modelo continua recebendo a pergunta original, e sem tema casado a consulta é
 * a de antes. Função pura; tabela em {@code ceres/vocabulario-lei.yml}.
 */
public final class ExpansaoConsultaLei {

  private static final String RECURSO = "/ceres/vocabulario-lei.yml";

  private static final List<Tema> TEMAS = carregar();

  private ExpansaoConsultaLei() {}

  /** Tema da tabela: grupos de padrões (qualquer grupo com todos os padrões casando) e os termos. */
  record Tema(String nome, List<List<Pattern>> gatilhos, String termos) {

    boolean casa(String pergunta) {
      return gatilhos.stream().anyMatch(grupo -> grupo.stream().allMatch(p -> p.matcher(pergunta).find()));
    }
  }

  /**
   * A consulta da busca para a pergunta: a própria pergunta seguida dos termos dos temas casados.
   *
   * @param pergunta pergunta do usuário
   * @return a pergunta sem alteração se nenhum tema casa
   */
  public static String expandir(String pergunta) {
    return expandir(pergunta, TEMAS);
  }

  /** Temas casados na pergunta, pelo nome (para o diagnóstico). */
  public static List<String> temasCasados(String pergunta) {
    String simples = NormalizacaoBusca.normalizar(pergunta);
    return TEMAS.stream().filter(t -> t.casa(simples)).map(Tema::nome).toList();
  }

  static String expandir(String pergunta, List<Tema> temas) {
    if (pergunta == null || pergunta.isBlank()) {
      return pergunta;
    }
    String simples = NormalizacaoBusca.normalizar(pergunta);
    Set<String> termos = new LinkedHashSet<>();
    temas.stream().filter(t -> t.casa(simples)).forEach(t -> termos.add(t.termos()));
    return termos.isEmpty() ? pergunta : pergunta + " " + String.join(". ", termos);
  }

  @SuppressWarnings("unchecked")
  static List<Tema> carregar() {
    try (InputStream in = ExpansaoConsultaLei.class.getResourceAsStream(RECURSO)) {
      if (in == null) {
        throw new IllegalStateException("Recurso " + RECURSO + " ausente.");
      }
      Map<String, Object> raiz =
          new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
      List<Tema> temas = new ArrayList<>();
      for (Map<String, Object> t : (List<Map<String, Object>>) raiz.get("temas")) {
        List<List<Pattern>> grupos = new ArrayList<>();
        for (List<String> grupo : (List<List<String>>) t.get("gatilhos")) {
          grupos.add(grupo.stream().map(Pattern::compile).toList());
        }
        temas.add(new Tema(String.valueOf(t.get("tema")), List.copyOf(grupos),
            String.valueOf(t.get("termos")).strip()));
      }
      return List.copyOf(temas);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
