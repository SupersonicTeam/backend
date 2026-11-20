package br.com.agrocontabil.model;

/**
 * Como foi a busca vetorial (o embedding da pergunta mais a consulta à base) da reconferência de
 * lei da continuidade (#499), gravada em {@code assistente_interacao.reconferencia_busca}
 * (changeSet 0066). Nulo quando a reconferência não chegou à busca vetorial: texto fixo, disjuntor
 * aberto, nenhum dispositivo citado ou falha na busca dos citados, que vem antes.
 *
 * <p>A reconferência não tem fallback textual para a busca vetorial: se ela falha, o desfecho é o
 * apoio sem modelo.
 */
public enum BuscaReconferenciaLei {
  /** A busca respondeu (com ou sem trecho acima do limiar). */
  OK,
  /** O embedding ou a base falharam: apoio sem modelo. */
  FALHOU
}
