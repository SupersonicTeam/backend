package br.com.agrocontabil.model;

/**
 * Desfecho da conferência de fidelidade da rota de lei da Ceres (#269), atrás da flag
 * {@code app.assistente.fidelidade-lei.enabled}. Gravado em {@code assistente_interacao.fidelidade_lei}
 * (changeSet 0065), só o enum, nunca o texto (#144). Nulo quando a conferência não roda: flag
 * desligada, outra rota, sem trecho, {@code NAO_ENCONTREI} ou resposta cortada pelo teto.
 */
public enum DesfechoFidelidadeLei {
  /** Toda frase sustentada pela própria evidência, citações como o modelo marcou. */
  APROVADA,
  /**
   * Toda frase sustentada, e ao menos uma citação foi corrigida pelo backend: subiu para o caput
   * (ou o parágrafo) onde a evidência está, ou foi para o trecho em que a evidência está escrita.
   */
  APROVADA_CITACAO_CORRIGIDA,
  /** A resposta não veio no formato pedido (JSON com frases e evidências) ou veio sem frase. */
  REPROVADA_FORMATO,
  /** Uma frase sem evidência, ou com evidência que aponta para trecho inexistente. */
  REPROVADA_SEM_EVIDENCIA,
  /** Uma evidência que não está escrita, palavra por palavra, no trecho (nem em outro). */
  REPROVADA_EVIDENCIA_NAO_LITERAL,
  /** Uma frase com número que não está na evidência dela. */
  REPROVADA_NUMERO,
  /**
   * Uma frase com negação ou restrição ("não", "só", "exceto") que a evidência não tem, ou o
   * contrário: a evidência nega e a frase não. É o sinal de sentido invertido.
   */
  REPROVADA_POLARIDADE,
  /** Uma frase com palavras de conteúdo demais fora da evidência: afirma além do trecho. */
  REPROVADA_COBERTURA
}
