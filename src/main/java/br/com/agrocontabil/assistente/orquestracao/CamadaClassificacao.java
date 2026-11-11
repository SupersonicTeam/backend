package br.com.agrocontabil.assistente.orquestracao;

/**
 * Qual camada decidiu a rota de uma pergunta (issue #265), da mais barata para a mais cara. Vai à
 * auditoria (sem o texto da pergunta) e ao relatório da avaliação, que mede a precisão de cada uma.
 */
public enum CamadaClassificacao {
  /**
   * {@link AtalhosFixos}: pergunta de atalho da tela, pelo texto normalizado exato (#343). Cabe no
   * {@code VARCHAR(20)} da coluna (changeSet 0050), sem changeSet novo.
   */
  ATALHO_FIXO,
  /**
   * {@link ConversaLeve}: saudação, agradecimento, despedida, reconhecimento, apresentação ou
   * seguimento, pela lista fechada (#493). Cabe no {@code VARCHAR(20)}, sem changeSet.
   */
  CONVERSA_LEVE,
  /** {@link FiltroInjecao}: forma de prompt injection → fora de escopo. */
  FILTRO_INJECAO,
  /** {@link RegrasClassificacao}: termo inequívoco de um lado só. */
  REGRA,
  /** {@link EmbeddingClassificadorIntencao}: vizinhos no conjunto de treino. */
  EMBEDDING,
  /** {@link LlmClassificadorIntencao}: desempate pelo modelo de linguagem. */
  LLM,
  /** Modelo fora do ar: palavras-chave do modo fallback (#146). */
  PALAVRAS_CHAVE
}
