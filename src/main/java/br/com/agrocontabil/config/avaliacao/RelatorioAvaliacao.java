package br.com.agrocontabil.config.avaliacao;

import br.com.agrocontabil.config.avaliacao.PerguntaGabarito.Categoria;
import br.com.agrocontabil.model.DesfechoInteracao;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Relatório da avaliação da Ceres (issue #158): as métricas da issue, por pergunta e consolidadas.
 *
 * @param ambiente   o que foi medido e onde (modelo, hardware, gabarito)
 * @param resultados vereditos, na ordem do gabarito
 */
public record RelatorioAvaliacao(Ambiente ambiente, List<ResultadoPergunta> resultados) {

  /** Desfechos de falha técnica: a pergunta não chegou a ser respondida pela Ceres. */
  static final Set<DesfechoInteracao> FALHAS_TECNICAS = EnumSet.of(
      DesfechoInteracao.MODELO_INDISPONIVEL, DesfechoInteracao.MODO_FALLBACK,
      DesfechoInteracao.TEMPO_ESGOTADO, DesfechoInteracao.CANCELADA, DesfechoInteracao.ERRO);

  /**
   * Condições da medição. Sem elas dois relatórios não são comparáveis.
   *
   * @param executadoEm     início da execução
   * @param hojeReferencia  data que a Ceres tomou como "hoje"
   * @param versaoGabarito  versão do gabarito
   * @param modelo          provedor/modelo de chat
   * @param modeloEmbedding modelo de embedding da busca de legislação
   * @param hardware        descrição do hardware (informada + detectada)
   * @param duracaoSegundos duração total
   */
  public record Ambiente(
      OffsetDateTime executadoEm, LocalDate hojeReferencia, String versaoGabarito,
      String modelo, String modeloEmbedding, List<String> hardware, long duracaoSegundos) {

    /** Cópia defensiva. */
    public Ambiente {
      hardware = hardware == null ? List.of() : List.copyOf(hardware);
    }
  }

  /**
   * Uma taxa: acertos sobre as perguntas em que o critério se aplica.
   *
   * @param acertos acertos
   * @param total   perguntas avaliadas no critério
   */
  public record Taxa(long acertos, long total) {

    /** Percentual com uma casa, ou "—" sem perguntas. */
    public String formatada() {
      return total == 0
          ? "—"
          : String.format(Locale.ROOT, "%.1f%% (%d/%d)", 100.0 * acertos / total, acertos, total);
    }
  }

  /** Cópia defensiva. */
  public RelatorioAvaliacao {
    resultados = List.copyOf(resultados);
  }

  /** Taxas da issue, na ordem em que aparecem no relatório. */
  public Map<String, Taxa> metricas() {
    Map<String, Taxa> metricas = new LinkedHashMap<>();
    metricas.put("Acurácia de rota", taxa(resultados, ResultadoPergunta::rotaCorreta));
    metricas.put("Acerto de função", taxa(resultados, ResultadoPergunta::funcaoCorreta));
    metricas.put("Acerto de parâmetros", taxa(resultados, ResultadoPergunta::parametrosCorretos));
    metricas.put("Exatidão numérica", taxa(resultados, ResultadoPergunta::numeroCorreto));
    metricas.put("Citação válida", taxa(resultados, ResultadoPergunta::citacaoValida));
    metricas.put("Recusa correta", taxa(resultados, ResultadoPergunta::recusaCorreta));
    metricas.put("Sem vazamento",
        taxa(resultados, r -> r.vazamento() == null ? null : !r.vazamento()));
    metricas.put("Sem falha técnica", new Taxa(
        resultados.stream().filter(r -> !falhaTecnica(r)).count(), resultados.size()));
    return metricas;
  }

  /** Latência em milissegundos no percentil (nearest-rank); 0 sem perguntas. */
  public static long percentil(List<ResultadoPergunta> resultados, int percentil) {
    List<Long> latencias = new ArrayList<>(
        resultados.stream().map(r -> r.execucao().latenciaMs()).sorted().toList());
    if (latencias.isEmpty()) {
      return 0;
    }
    int posicao = (int) Math.ceil(percentil / 100.0 * latencias.size());
    return latencias.get(Math.max(0, posicao - 1));
  }

  static boolean falhaTecnica(ResultadoPergunta r) {
    return r.execucao().erro() != null || FALHAS_TECNICAS.contains(r.execucao().desfecho());
  }

  private static Taxa taxa(
      List<ResultadoPergunta> resultados, Function<ResultadoPergunta, Boolean> criterio) {
    long total = resultados.stream().map(criterio).filter(v -> v != null).count();
    long acertos = resultados.stream().map(criterio).filter(Boolean.TRUE::equals).count();
    return new Taxa(acertos, total);
  }

  /** Relatório em Markdown, para versionar em {@code docs/} e levar ao artigo. */
  public String markdown() {
    StringBuilder md = new StringBuilder();
    md.append("# Avaliação da Ceres — ").append(ambiente.modelo()).append("\n\n");
    md.append("Gerado pelo comando de avaliação (issue #158). Não editar à mão: rode de novo. ");
    md.append("Uma pergunta de aquecimento roda antes e é descartada (carga do modelo).\n\n");
    md.append("## Condições\n\n");
    md.append("| Item | Valor |\n|---|---|\n");
    md.append("| Executado em | ").append(ambiente.executadoEm()).append(" |\n");
    md.append("| \"Hoje\" da Ceres | ").append(ambiente.hojeReferencia()).append(" |\n");
    md.append("| Gabarito | ").append(ambiente.versaoGabarito())
        .append(" (").append(resultados.size()).append(" perguntas) |\n");
    md.append("| Modelo de chat | ").append(ambiente.modelo()).append(" |\n");
    md.append("| Embedding | ").append(ambiente.modeloEmbedding()).append(" |\n");
    for (String linha : ambiente.hardware()) {
      md.append("| Hardware | ").append(escapar(linha)).append(" |\n");
    }
    md.append("| Duração total | ").append(ambiente.duracaoSegundos()).append(" s |\n\n");

    md.append("## Métricas\n\n| Métrica | Resultado |\n|---|---|\n");
    metricas().forEach((nome, taxa) ->
        md.append("| ").append(nome).append(" | ").append(taxa.formatada()).append(" |\n"));
    md.append("| Latência p50 | ").append(segundos(percentil(resultados, 50))).append(" |\n");
    md.append("| Latência p95 | ").append(segundos(percentil(resultados, 95))).append(" |\n\n");

    md.append("## Por categoria\n\n");
    md.append("| Categoria | Perguntas | Rota | Número | Citação | Recusa | p50 | p95 |\n");
    md.append("|---|---|---|---|---|---|---|---|\n");
    for (Categoria categoria : Categoria.values()) {
      List<ResultadoPergunta> daCategoria =
          resultados.stream().filter(r -> r.categoria() == categoria).toList();
      if (daCategoria.isEmpty()) {
        continue;
      }
      md.append("| ").append(categoria).append(" | ").append(daCategoria.size())
          .append(" | ").append(taxa(daCategoria, ResultadoPergunta::rotaCorreta).formatada())
          .append(" | ").append(taxa(daCategoria, ResultadoPergunta::numeroCorreto).formatada())
          .append(" | ").append(taxa(daCategoria, ResultadoPergunta::citacaoValida).formatada())
          .append(" | ").append(taxa(daCategoria, ResultadoPergunta::recusaCorreta).formatada())
          .append(" | ").append(segundos(percentil(daCategoria, 50)))
          .append(" | ").append(segundos(percentil(daCategoria, 95))).append(" |\n");
    }

    md.append("\n## Por pergunta\n\n");
    md.append("Legenda: ✓ acertou, ✗ errou, · não se aplica. ");
    md.append("As respostas completas estão no JSON ao lado.\n\n");
    md.append("| Id | Rota obtida | Desfecho | Rota | Função | Parâm. | Número | Citação | "
        + "Recusa | Vazou | Latência |\n");
    md.append("|---|---|---|---|---|---|---|---|---|---|---|\n");
    for (ResultadoPergunta r : resultados) {
      md.append("| ").append(r.id())
          .append(" | ").append(r.execucao().rota() == null ? "—" : r.execucao().rota())
          .append(" | ").append(r.execucao().erro() != null
              ? "erro: " + r.execucao().erro()
              : r.execucao().desfecho())
          .append(" | ").append(marca(r.rotaCorreta()))
          .append(" | ").append(marca(r.funcaoCorreta()))
          .append(" | ").append(marca(r.parametrosCorretos()))
          .append(" | ").append(marca(r.numeroCorreto()))
          .append(" | ").append(marca(r.citacaoValida()))
          .append(" | ").append(marca(r.recusaCorreta()))
          .append(" | ").append(r.vazamento() == null ? "·" : (r.vazamento() ? "**sim**" : "não"))
          .append(" | ").append(segundos(r.execucao().latenciaMs())).append(" |\n");
    }
    return md.toString();
  }

  private static String marca(Boolean valor) {
    return valor == null ? "·" : (valor ? "✓" : "✗");
  }

  private static String segundos(long ms) {
    return String.format(Locale.ROOT, "%.1f s", ms / 1000.0);
  }

  private static String escapar(String texto) {
    return texto.replace("|", "\\|").replace("\n", " ");
  }
}
