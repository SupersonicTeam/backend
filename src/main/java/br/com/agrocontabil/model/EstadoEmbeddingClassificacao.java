package br.com.agrocontabil.model;

/**
 * O que aconteceu com o embedding da pergunta na camada de embeddings da classificação de rota da
 * Ceres (#624). Gravado em {@code assistente_interacao.embedding_classificacao} (changeSet 0067),
 * só o enum, nunca o texto (#144). Nulo quando a camada não foi consultada: a regra, um atalho ou
 * o sinal de recusa decidiu antes, a marca de outra pessoa mandou direto ao modelo, ou as camadas
 * estão desligadas.
 *
 * <p>Fora de {@link #RESPONDEU}, a camada não votou e a rota foi do modelo (ou do modo sem
 * modelo): é o rastro de que a camada {@code LLM} da auditoria veio de uma pausa, e não de um voto
 * fraco das embeddings.
 */
public enum EstadoEmbeddingClassificacao {
  /** O embedding voltou no prazo e a camada votou (o voto pode não ter decidido: limiar). */
  RESPONDEU,
  /** O embedding passou do {@code timeout-embedding}: a camada entra em pausa a partir daqui. */
  PRAZO_VENCIDO,
  /** O embedding falhou (Ollama fora, erro do cliente): a camada entra em pausa a partir daqui. */
  FALHA,
  /** A camada estava em pausa por uma falha ou prazo vencido anterior: nem chamou o embedding. */
  EM_PAUSA,
  /** O pool do embedding estava tomado (2 em curso, 8 na fila): a pergunta não esperou. */
  FILA_CHEIA,
  /** Os vetores do treino ainda não estavam prontos (subida, Ollama fora no aquecimento). */
  SEM_VETORES,
  /** A pergunta foi interrompida (prazo da pergunta, cliente saiu) esperando o embedding. */
  INTERROMPIDA
}
