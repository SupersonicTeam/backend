package br.com.agrocontabil.config;

/**
 * Caminho de uma pergunta à Ceres, para a reserva da cota (#166, por caminho desde a #499). Cada
 * caminho diz quantas chamadas ao modelo a pergunta pode fazer e quais tetos de saída
 * ({@code app.assistente.tetos}) elas usam; a reserva é o custo máximo dele.
 *
 * <p>Com os padrões (8192 de entrada por chamada, peso de saída 4, 1 token por crédito desde a
 * #511, fator 1): {@link #NORMAL} 31.744, {@link #RECONFERENCIA} 24.576,
 * {@link #RECONFERENCIA_MISTO} 34.816 e {@link #REESCRITA} 43.776. {@link #SEM_MODELO} não reserva
 * teto: paga a leitura da mensagem ({@code CotaUsoAssistenteService.reservarLeitura}). Com o
 * juiz da lei (#269) podendo rodar, todo caminho com modelo ganha a parcela dele
 * ({@code AssistenteProperties.tetoDaPergunta(fator, caminho)}).
 */
public enum CaminhoCota {

  /**
   * Nenhuma chamada: atalho da tela, conversa leve, confirmação de dados cuja consulta assinada
   * bate (reexecução sem modelo) e os textos fixos de lei da reconferência. Paga só a leitura da
   * mensagem (#511).
   */
  SEM_MODELO,

  /**
   * Pergunta sem reescrita (também com a continuidade ligada) e confirmação de dados com consulta
   * que não bate: classificação, funções e lei.
   */
  NORMAL,

  /**
   * Reconferência de lei: a reconferência e a rota de lei da concordância. Só com a correção por
   * texto ligada ({@code app.assistente.continuidade.lei-corrijo-por-texto}); sem ela, a reserva é
   * a de {@link #RECONFERENCIA_APOIO}.
   */
  RECONFERENCIA,

  /**
   * Reconferência no MISTO cuja consulta não bate: funções, reconferência e lei. Só com a correção
   * por texto ligada; sem ela, a reserva é a de {@link #RECONFERENCIA_MISTO_APOIO}.
   */
  RECONFERENCIA_MISTO,

  /**
   * Reconferência de lei sem a correção por texto (#584, o padrão): uma chamada, a reconferência.
   * O {@code CORRIJO} do modelo vira o apoio e a concordância não roda.
   */
  RECONFERENCIA_APOIO,

  /** Reconferência no MISTO sem a correção por texto (#584): funções e reconferência. */
  RECONFERENCIA_MISTO_APOIO,

  /** Com reescrita: a reescrita e o caminho mais caro depois dela. */
  REESCRITA,

  /**
   * Confirmação de dados em que o produto da consulta anterior não aparece na pergunta e a tabela
   * de produto não decide (#591): o julgamento de produto e, se ele achar outro produto, o caminho
   * normal de dados. Só com o julgamento ligado; sem ele, a confirmação é a reexecução sem modelo.
   */
  CONFIRMACAO_COM_JULGAMENTO;

  /**
   * Chamadas ao modelo, no máximo.
   *
   * @return quantas chamadas o caminho pode fazer
   */
  public int chamadas() {
    return switch (this) {
      case SEM_MODELO -> 0;
      case RECONFERENCIA_APOIO -> 1;
      case RECONFERENCIA, RECONFERENCIA_MISTO_APOIO -> 2;
      case NORMAL, RECONFERENCIA_MISTO -> 3;
      case REESCRITA, CONFIRMACAO_COM_JULGAMENTO -> 4;
    };
  }

  /**
   * Soma dos tetos de saída das chamadas do caminho.
   *
   * @param tetos tetos por tipo de chamada
   * @return tokens de saída, no máximo
   */
  public long saida(AssistenteProperties.Tetos tetos) {
    long normal = (long) tetos.getClassificacao() + tetos.getFuncoes() + tetos.getLegislacao();
    long reconferenciaMisto =
        (long) tetos.getFuncoes() + tetos.getReconferencia() + tetos.getLegislacao();
    return switch (this) {
      case SEM_MODELO -> 0;
      case NORMAL -> normal;
      case RECONFERENCIA -> (long) tetos.getReconferencia() + tetos.getLegislacao();
      case RECONFERENCIA_MISTO -> reconferenciaMisto;
      case RECONFERENCIA_APOIO -> tetos.getReconferencia();
      case RECONFERENCIA_MISTO_APOIO -> (long) tetos.getFuncoes() + tetos.getReconferencia();
      case REESCRITA -> tetos.getReescrita() + Math.max(normal, reconferenciaMisto);
      case CONFIRMACAO_COM_JULGAMENTO -> tetos.getJulgamentoProduto() + normal;
    };
  }
}
