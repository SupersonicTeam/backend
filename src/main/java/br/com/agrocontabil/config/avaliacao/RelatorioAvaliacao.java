package br.com.agrocontabil.config.avaliacao;

import br.com.agrocontabil.assistente.orquestracao.Intencao;
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
   * @param versaoSeed      versão do seed de avaliação ({@link SeedAvaliacao#VERSAO}): o mesmo
   *                        gabarito sobre outro seed mede outra coisa
   * @param modelo          provedor/modelo de chat
   * @param modeloEmbedding modelo de embedding da busca de legislação
   * @param hardware        descrição do hardware (informada + detectada)
   * @param duracaoSegundos duração total
   */
  public record Ambiente(
      OffsetDateTime executadoEm, LocalDate hojeReferencia, String versaoGabarito,
      String versaoSeed, String modelo, String modeloEmbedding, List<String> hardware, long duracaoSegundos) {

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

  /** Rótulo do grupo das perguntas sem rota esperada (ataques que só medem recusa e vazamento). */
  static final String SEM_ROTA = "SEM_ROTA";

  /**
   * Indicadores de uma rota esperada (issue #264). Clareza só sobre as respostas redigidas pelo
   * modelo (desfecho {@code RESPONDIDA}): a frase fixa de uma recusa não mede a redação.
   *
   * @param perguntas            perguntas com essa rota esperada
   * @param rota                 acerto de rota
   * @param numero               acerto de número
   * @param citacao              citação válida
   * @param latenciaP50Ms        latência p50
   * @param latenciaP95Ms        latência p95
   * @param primeiraParteP50Ms   p50 do tempo até a primeira parte da resposta
   * @param primeiraParteP95Ms   p95 do tempo até a primeira parte da resposta
   * @param respostasMedidas     respostas redigidas pelo modelo
   * @param palavrasPorFrase     média de palavras por frase nessas respostas
   * @param comSiglaSemExplicacao respostas com alguma sigla do glossário sem explicação
   */
  public record IndicadoresRota(int perguntas, Taxa rota, Taxa numero, Taxa citacao,
      long latenciaP50Ms, long latenciaP95Ms, long primeiraParteP50Ms, long primeiraParteP95Ms,
      long respostasMedidas, double palavrasPorFrase, long comSiglaSemExplicacao) {}

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
    metricas.put("Sem beco sem saída", taxa(resultados, ResultadoPergunta::semBecoSemSaida));
    metricas.put("Sem falha técnica", new Taxa(
        resultados.stream().filter(r -> !falhaTecnica(r.execucao())).count(),
        resultados.size()));
    return metricas;
  }

  /** Acerto de rota nos casos adversários (issue #264). */
  public Taxa rotaAdversarios() {
    return taxa(resultados.stream().filter(ResultadoPergunta::adversario).toList(),
        ResultadoPergunta::rotaCorreta);
  }

  /** Indicadores por rota esperada, na ordem das rotas; sem rota esperada no fim. */
  public Map<String, IndicadoresRota> porRota() {
    Map<String, IndicadoresRota> porRota = new LinkedHashMap<>();
    for (Intencao rota : Intencao.values()) {
      List<ResultadoPergunta> daRota =
          resultados.stream().filter(r -> r.rotaEsperada() == rota).toList();
      if (!daRota.isEmpty()) {
        porRota.put(rota.name(), indicadores(daRota));
      }
    }
    List<ResultadoPergunta> semRota =
        resultados.stream().filter(r -> r.rotaEsperada() == null).toList();
    if (!semRota.isEmpty()) {
      porRota.put(SEM_ROTA, indicadores(semRota));
    }
    return porRota;
  }

  static IndicadoresRota indicadores(List<ResultadoPergunta> grupo) {
    List<ClarezaTexto.Medida> medidas = grupo.stream()
        .filter(r -> r.execucao().erro() == null
            && (r.execucao().desfecho() == DesfechoInteracao.RESPONDIDA
                || r.execucao().desfecho() == DesfechoInteracao.NEGATIVA_SUBSTITUIDA))
        .map(r -> ClarezaTexto.medir(r.execucao().texto()))
        .filter(m -> m.frases() > 0)
        .toList();
    List<Long> primeiraParte = grupo.stream().map(ResultadoPergunta::primeiraParteMs).toList();
    return new IndicadoresRota(grupo.size(),
        taxa(grupo, ResultadoPergunta::rotaCorreta),
        taxa(grupo, ResultadoPergunta::numeroCorreto),
        taxa(grupo, ResultadoPergunta::citacaoValida),
        percentil(grupo, 50), percentil(grupo, 95),
        percentilDe(primeiraParte, 50), percentilDe(primeiraParte, 95),
        medidas.size(),
        medidas.stream().mapToDouble(ClarezaTexto.Medida::palavrasPorFrase).average().orElse(0),
        medidas.stream().filter(m -> !m.siglasSemExplicacao().isEmpty()).count());
  }

  /** Latência em milissegundos no percentil (nearest-rank); 0 sem perguntas. */
  public static long percentil(List<ResultadoPergunta> resultados, int percentil) {
    return percentilDe(resultados.stream().map(r -> r.execucao().latenciaMs()).toList(),
        percentil);
  }

  /** Percentil (nearest-rank) de uma lista de tempos; 0 se vazia. */
  static long percentilDe(List<Long> valores, int percentil) {
    List<Long> latencias = new ArrayList<>(valores.stream().sorted().toList());
    if (latencias.isEmpty()) {
      return 0;
    }
    int posicao = (int) Math.ceil(percentil / 100.0 * latencias.size());
    return latencias.get(Math.max(0, posicao - 1));
  }

  /**
   * A pergunta não foi respondida pelo modelo: erro, desfecho técnico ou alguma parte montada no
   * modo fallback (um MISTO com a lei em fallback sai como RESPONDIDA, mas não mede o modelo).
   */
  static boolean falhaTecnica(ExecucaoPergunta execucao) {
    return execucao.erro() != null || execucao.modoFallback()
        || FALHAS_TECNICAS.contains(execucao.desfecho());
  }

  static Taxa taxa(
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
    md.append("| Seed | ").append(ambiente.versaoSeed()).append(" |\n");
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

    md.append("\n## Por rota esperada\n\n");
    md.append(tabelaPorRota(porRota()));
    md.append("\nCasos adversários de rota: ").append(rotaAdversarios().formatada())
        .append(" de acerto de rota.\n");

    md.append("\n## Por camada da classificação (#265)\n\n");
    md.append(tabelaPorCamada(porCamada()));

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
              : r.execucao().desfecho()).append(partesMisto(r.execucao()))
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

  /**
   * Precisão de cada camada da classificação (#265): quantas perguntas com rota esperada ela
   * decidiu e quantas acertou. A regra precisa de zero erros; o desempate pelo modelo vira
   * proporção do total.
   *
   * @return camada → [decididas, acertos]; {@value #SEM_ROTA} para pergunta que falhou antes da classificação
   */
  public Map<String, int[]> porCamada() {
    Map<String, int[]> porCamada = new LinkedHashMap<>();
    for (ResultadoPergunta r : resultados) {
      if (r.rotaCorreta() == null) {
        continue;
      }
      String camada = r.execucao().camadaClassificacao() == null
          ? SEM_ROTA : r.execucao().camadaClassificacao();
      int[] contagem = porCamada.computeIfAbsent(camada, k -> new int[2]);
      contagem[0]++;
      if (Boolean.TRUE.equals(r.rotaCorreta())) {
        contagem[1]++;
      }
    }
    return porCamada;
  }

  static String tabelaPorCamada(Map<String, int[]> porCamada) {
    int total = porCamada.values().stream().mapToInt(c -> c[0]).sum();
    StringBuilder md = new StringBuilder(
        "| Camada | Decidiu | Acertou | Erros | Parcela do total |\n|---|---|---|---|---|\n");
    porCamada.forEach((camada, c) -> md.append("| ").append(camada)
        .append(" | ").append(c[0])
        .append(" | ").append(c[1])
        .append(" | ").append(c[0] - c[1])
        .append(" | ").append(total == 0 ? "-" : String.format(java.util.Locale.ROOT, "%.0f%%",
            100.0 * c[0] / total))
        .append(" |\n"));
    return md.toString();
  }

  /**
   * Tabela por rota esperada. "1ª parte" é o tempo até a primeira parte da resposta: até a etapa 6
   * da Ceres 2.0 (resposta de dados em partes) ela é a resposta inteira, então coincide com a
   * latência; a coluna existe para a comparação daquela etapa.
   */
  static String tabelaPorRota(Map<String, IndicadoresRota> porRota) {
    return tabelaPorRota(porRota, "Perguntas");
  }

  /**
   * Mesma tabela com outro rótulo para a contagem: com as rodadas juntas, cada linha conta
   * respostas (perguntas × rodadas), não perguntas.
   */
  static String tabelaPorRota(Map<String, IndicadoresRota> porRota, String rotuloContagem) {
    StringBuilder md = new StringBuilder();
    md.append("| Rota esperada | ").append(rotuloContagem).append(" | Rota | Número | Citação | p50 | p95 | 1ª parte p50 | "
        + "1ª parte p95 | Palavras/frase | Sigla sem explicação |\n");
    md.append("|---|---|---|---|---|---|---|---|---|---|---|\n");
    porRota.forEach((rota, i) -> md.append("| ").append(rota)
        .append(" | ").append(i.perguntas())
        .append(" | ").append(i.rota().formatada())
        .append(" | ").append(i.numero().formatada())
        .append(" | ").append(i.citacao().formatada())
        .append(" | ").append(segundos(i.latenciaP50Ms()))
        .append(" | ").append(segundos(i.latenciaP95Ms()))
        .append(" | ").append(segundos(i.primeiraParteP50Ms()))
        .append(" | ").append(segundos(i.primeiraParteP95Ms()))
        .append(" | ").append(i.respostasMedidas() == 0
            ? "—" : String.format(Locale.ROOT, "%.1f", i.palavrasPorFrase()))
        .append(" | ").append(i.respostasMedidas() == 0
            ? "—" : i.comSiglaSemExplicacao() + "/" + i.respostasMedidas())
        .append(" |\n"));
    return md.toString();
  }

  /** No MISTO, o desfecho de cada parte: mostra qual das duas foi descartada. */
  static String partesMisto(ExecucaoPergunta execucao) {
    if (execucao.desfechoDados() == null && execucao.desfechoLegislacao() == null) {
      return "";
    }
    return " (dados " + (execucao.desfechoDados() == null ? "—" : execucao.desfechoDados())
        + ", lei " + (execucao.desfechoLegislacao() == null ? "—" : execucao.desfechoLegislacao())
        + (Boolean.TRUE.equals(execucao.perguntaLeiReescrita()) ? ", reescrita" : "") + ")";
  }

  private static String marca(Boolean valor) {
    return valor == null ? "·" : (valor ? "✓" : "✗");
  }

  static String segundos(long ms) {
    return String.format(Locale.ROOT, "%.1f s", ms / 1000.0);
  }

  private static String escapar(String texto) {
    return texto.replace("|", "\\|").replace("\n", " ");
  }
}
