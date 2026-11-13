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

  /** Reconferência de lei: a reconferência e a rota de lei da concordância. */
  RECONFERENCIA,

  /** Reconferência no MISTO cuja consulta não bate: funções, reconferência e lei. */
  RECONFERENCIA_MISTO,

  /** Com reescrita: a reescrita e o caminho mais caro depois dela. */
  REESCRITA;

  /**
   * Chamadas ao modelo, no máximo.
   *
   * @return quantas chamadas o caminho pode fazer
   */
  public int chamadas() {
    return switch (this) {
      case SEM_MODELO -> 0;
      case RECONFERENCIA -> 2;
      case NORMAL, RECONFERENCIA_MISTO -> 3;
      case REESCRITA -> 4;
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
      case REESCRITA -> tetos.getReescrita() + Math.max(normal, reconferenciaMisto);
    };
  }
}
