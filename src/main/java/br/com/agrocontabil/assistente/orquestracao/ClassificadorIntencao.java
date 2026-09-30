package br.com.agrocontabil.assistente.orquestracao;

import br.com.agrocontabil.assistente.ContextoAssistente;

/**
 * Identifica a intenção de uma pergunta à Ceres (issue #157).
 *
 * <p>Interface plugável, no mesmo padrão do {@code SefazTransport}: a Fase 1 usa o próprio modelo
 * de linguagem ({@link LlmClassificadorIntencao}); a Fase 2 pluga um classificador próprio
 * (BERTimbau, em repositório Python) como outra implementação, sem mudar o orquestrador.
 *
 * <p>Implementações não lançam exceção por pergunta ambígua ou resposta ilegível: devolvem
 * {@link ClassificacaoIntencao#indeterminada()}. Falha de infraestrutura (modelo fora do ar) sobe
 * como {@code AssistenteIndisponivelException}.
 */
public interface ClassificadorIntencao {

  /**
   * Classifica a pergunta.
   *
   * @param pergunta pergunta do usuário
   * @param contexto contexto da pergunta (data de referência e contabilidade de uso)
   * @return intenção e confiança
   */
  ClassificacaoIntencao classificar(String pergunta, ContextoAssistente contexto);
}
