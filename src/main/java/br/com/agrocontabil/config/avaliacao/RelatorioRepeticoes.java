package br.com.agrocontabil.config.avaliacao;

import br.com.agrocontabil.config.avaliacao.RelatorioAvaliacao.IndicadoresRota;
import br.com.agrocontabil.config.avaliacao.RelatorioAvaliacao.Taxa;
import java.util.ArrayList;
import java.util.DoubleSummaryStatistics;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Avaliação da Ceres com repetições (issue #264, absorve a #201). Uma rodada só não separa ganho
 * de ruído: à temperatura 0,2, uma pergunta MISTO vale 14 pontos percentuais, e há pergunta que
 * muda de resultado entre rodadas sem mudança de código. O relatório mostra a média e a faixa
 * (mínimo–máximo) de cada métrica e a lista das perguntas instáveis.
 *
 * @param rodadas uma avaliação completa do gabarito por rodada, na ordem em que rodaram
 */
public record RelatorioRepeticoes(List<RelatorioAvaliacao> rodadas) {

  /** Critérios comparados entre rodadas para achar as perguntas instáveis. */
  private static final Map<String, Function<ResultadoPergunta, Boolean>> CRITERIOS = criterios();

  /**
   * Média e faixa de uma métrica entre as rodadas, em percentual.
   *
   * @param media  média dos percentuais das rodadas
   * @param minimo menor percentual
   * @param maximo maior percentual
   * @param total  perguntas avaliadas no critério em cada rodada (igual entre rodadas)
   */
  public record Faixa(double media, double minimo, double maximo, long total) {

    /** "81,8% (77,3–86,4%)", ou "—" se o critério não se aplica a nenhuma pergunta. */
    public String formatada() {
      if (total == 0) {
        return "—";
      }
      return minimo == maximo
          ? String.format(Locale.ROOT, "%.1f%% (n=%d)", media, total)
          : String.format(Locale.ROOT, "%.1f%% (%.1f–%.1f%%, n=%d)", media, minimo, maximo,
              total);
    }
  }

  /** Ao menos uma rodada, todas sobre o mesmo gabarito e o mesmo seed. */
  public RelatorioRepeticoes {
    if (rodadas == null || rodadas.isEmpty()) {
      throw new IllegalArgumentException("sem rodadas");
    }
    rodadas = List.copyOf(rodadas);
    RelatorioAvaliacao.Ambiente primeira = rodadas.get(0).ambiente();
    for (RelatorioAvaliacao r : rodadas) {
      if (!Objects.equals(r.ambiente().versaoGabarito(), primeira.versaoGabarito())
          || !Objects.equals(r.ambiente().versaoSeed(), primeira.versaoSeed())
          || r.resultados().size() != rodadas.get(0).resultados().size()) {
        throw new IllegalArgumentException("rodadas de gabaritos ou seeds diferentes");
      }
    }
  }

  /** Média e faixa de cada métrica do {@link RelatorioAvaliacao#metricas()}. */
  public Map<String, Faixa> metricas() {
    Map<String, Faixa> metricas = new LinkedHashMap<>();
    for (String nome : rodadas.get(0).metricas().keySet()) {
      metricas.put(nome, faixa(rodadas.stream().map(r -> r.metricas().get(nome)).toList()));
    }
    metricas.put("Rota nos casos adversários",
        faixa(rodadas.stream().map(RelatorioAvaliacao::rotaAdversarios).toList()));
    return metricas;
  }

  /** Média e faixa de rota, número e citação por rota esperada. */
  public Map<String, Map<String, Faixa>> porRota() {
    Map<String, Map<String, Faixa>> porRota = new LinkedHashMap<>();
    // Uma vez por rodada: cada porRota() mede a clareza de todas as respostas de novo.
    List<Map<String, IndicadoresRota>> porRodada =
        rodadas.stream().map(RelatorioAvaliacao::porRota).toList();
    for (String rota : porRodada.get(0).keySet()) {
      List<IndicadoresRota> daRota = porRodada.stream().map(r -> r.get(rota)).toList();
      Map<String, Faixa> faixas = new LinkedHashMap<>();
      faixas.put("Rota", faixa(daRota.stream().map(IndicadoresRota::rota).toList()));
      faixas.put("Número", faixa(daRota.stream().map(IndicadoresRota::numero).toList()));
      faixas.put("Citação", faixa(daRota.stream().map(IndicadoresRota::citacao).toList()));
      porRota.put(rota, faixas);
    }
    return porRota;
  }

  /**
   * Indicadores por rota com todas as rodadas juntas: latência e clareza saem das respostas de
   * todas as rodadas (percentil sobre 3 vezes mais amostras, em vez da média de percentis).
   */
  public Map<String, IndicadoresRota> porRotaAgregado() {
    return todas().porRota();
  }

  /**
   * Perguntas cujo resultado mudou entre rodadas em algum critério, com os critérios que mudaram.
   * Com uma rodada só, vazio.
   */
  public Map<String, List<String>> instaveis() {
    Map<String, List<String>> instaveis = new LinkedHashMap<>();
    List<ResultadoPergunta> base = rodadas.get(0).resultados();
    for (int i = 0; i < base.size(); i++) {
      int indice = i;
      List<String> mudaram = new ArrayList<>();
      CRITERIOS.forEach((nome, criterio) -> {
        Set<Boolean> valores = new LinkedHashSet<>();
        rodadas.forEach(r -> valores.add(criterio.apply(r.resultados().get(indice))));
        if (valores.size() > 1) {
          mudaram.add(nome);
        }
      });
      if (!mudaram.isEmpty()) {
        instaveis.put(base.get(i).id(), mudaram);
      }
    }
    return instaveis;
  }

  /** As respostas de todas as rodadas num relatório só (para latência e clareza agregadas). */
  RelatorioAvaliacao todas() {
    List<ResultadoPergunta> todos = new ArrayList<>();
    rodadas.forEach(r -> todos.addAll(r.resultados()));
    return new RelatorioAvaliacao(rodadas.get(0).ambiente(), todos);
  }

  static Faixa faixa(List<Taxa> taxas) {
    List<Taxa> aplicaveis = taxas.stream().filter(t -> t.total() > 0).toList();
    if (aplicaveis.isEmpty()) {
      return new Faixa(0, 0, 0, 0);
    }
    DoubleSummaryStatistics estatistica = aplicaveis.stream()
        .mapToDouble(t -> 100.0 * t.acertos() / t.total()).summaryStatistics();
    return new Faixa(estatistica.getAverage(), estatistica.getMin(), estatistica.getMax(),
        aplicaveis.get(0).total());
  }

  /** Relatório em Markdown: condições, métricas com faixa, por rota, instáveis e cada rodada. */
  public String markdown() {
    RelatorioAvaliacao primeira = rodadas.get(0);
    RelatorioAvaliacao.Ambiente ambiente = primeira.ambiente();
    StringBuilder md = new StringBuilder();
    md.append("# Avaliação da Ceres — ").append(ambiente.modelo()).append(" — ")
        .append(rodadas.size()).append(rodadas.size() == 1 ? " rodada" : " rodadas").append("\n\n");
    md.append("Gerado pelo comando de avaliação (issues #158 e #264). Não editar à mão: rode de "
        + "novo. Cada métrica é a média das rodadas, com a faixa (mínimo–máximo) entre parênteses; "
        + "n = perguntas avaliadas no critério em cada rodada.\n\n");
    md.append("## Condições\n\n| Item | Valor |\n|---|---|\n");
    md.append("| Executado em | ").append(ambiente.executadoEm()).append(" |\n");
    md.append("| \"Hoje\" da Ceres | ").append(ambiente.hojeReferencia()).append(" |\n");
    md.append("| Gabarito | ").append(ambiente.versaoGabarito()).append(" (")
        .append(primeira.resultados().size()).append(" perguntas) |\n");
    md.append("| Seed | ").append(ambiente.versaoSeed()).append(" |\n");
    md.append("| Repetições | ").append(rodadas.size()).append(" |\n");
    md.append("| Modelo de chat | ").append(ambiente.modelo()).append(" |\n");
    md.append("| Embedding | ").append(ambiente.modeloEmbedding()).append(" |\n");
    for (String linha : ambiente.hardware()) {
      md.append("| Hardware | ").append(linha.replace("|", "\\|").replace("\n", " "))
          .append(" |\n");
    }
    md.append("| Duração total | ")
        .append(rodadas.stream().mapToLong(r -> r.ambiente().duracaoSegundos()).sum())
        .append(" s |\n\n");

    md.append("## Métricas\n\n| Métrica | Média (faixa) |\n|---|---|\n");
    metricas().forEach((nome, faixa) ->
        md.append("| ").append(nome).append(" | ").append(faixa.formatada()).append(" |\n"));
    RelatorioAvaliacao todas = todas();
    md.append("| Latência p50 (todas as rodadas) | ")
        .append(RelatorioAvaliacao.segundos(RelatorioAvaliacao.percentil(todas.resultados(), 50)))
        .append(" |\n");
    md.append("| Latência p95 (todas as rodadas) | ")
        .append(RelatorioAvaliacao.segundos(RelatorioAvaliacao.percentil(todas.resultados(), 95)))
        .append(" |\n\n");

    md.append("## Por rota esperada\n\n");
    md.append("| Rota esperada | Rota | Número | Citação |\n|---|---|---|---|\n");
    porRota().forEach((rota, faixas) -> md.append("| ").append(rota)
        .append(" | ").append(faixas.get("Rota").formatada())
        .append(" | ").append(faixas.get("Número").formatada())
        .append(" | ").append(faixas.get("Citação").formatada()).append(" |\n"));
    md.append("\nLatência e clareza com as respostas de todas as rodadas juntas (cada linha conta "
        + "respostas = perguntas × rodadas; as taxas aqui são do conjunto, a faixa está acima):"
        + "\n\n");
    md.append(RelatorioAvaliacao.tabelaPorRota(porRotaAgregado(), "Respostas"));

    md.append("\n## Perguntas instáveis entre rodadas\n\n");
    Map<String, List<String>> instaveis = instaveis();
    if (rodadas.size() == 1) {
      md.append("Uma rodada só: sem comparação. Toda medição registrada em `docs/avaliacao/` usa "
          + "3 repetições (`--avaliacao.repeticoes=3`).\n");
    } else if (instaveis.isEmpty()) {
      md.append("Nenhuma: todas as perguntas tiveram o mesmo resultado nas ")
          .append(rodadas.size()).append(" rodadas.\n");
    } else {
      md.append(instaveis.size()).append(" de ").append(primeira.resultados().size())
          .append(" perguntas mudaram de resultado:\n\n| Id | Critérios que mudaram |\n|---|---|\n");
      instaveis.forEach((id, criterios) -> md.append("| ").append(id).append(" | ")
          .append(String.join(", ", criterios)).append(" |\n"));
    }

    for (int i = 0; i < rodadas.size(); i++) {
      md.append("\n---\n\n## Rodada ").append(i + 1).append("\n\n");
      String daRodada = rodadas.get(i).markdown();
      // Tira o título e as condições da rodada: já estão no topo.
      int metricasRodada = daRodada.indexOf("## Métricas");
      md.append(daRodada.substring(Math.max(0, metricasRodada)).replace("## ", "### "));
    }
    return md.toString();
  }

  private static Map<String, Function<ResultadoPergunta, Boolean>> criterios() {
    Map<String, Function<ResultadoPergunta, Boolean>> c = new LinkedHashMap<>();
    c.put("rota", ResultadoPergunta::rotaCorreta);
    c.put("função", ResultadoPergunta::funcaoCorreta);
    c.put("parâmetros", ResultadoPergunta::parametrosCorretos);
    c.put("número", ResultadoPergunta::numeroCorreto);
    c.put("citação", ResultadoPergunta::citacaoValida);
    c.put("recusa", ResultadoPergunta::recusaCorreta);
    c.put("vazamento", ResultadoPergunta::vazamento);
    c.put("beco sem saída", ResultadoPergunta::semBecoSemSaida);
    c.put("falha técnica", r -> RelatorioAvaliacao.falhaTecnica(r.execucao()));
    return c;
  }
}
