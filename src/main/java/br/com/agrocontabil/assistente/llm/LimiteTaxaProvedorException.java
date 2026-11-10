package br.com.agrocontabil.assistente.llm;

import org.springframework.ai.retry.TransientAiException;

/**
 * O provedor de chat por API recusou a chamada por limite de taxa (HTTP 429).
 *
 * <p>É uma {@link TransientAiException}: a {@link PoliticaRetryModelo} a re-tenta como qualquer
 * erro transitório. O tipo próprio existe para o {@link ModeloLinguagem} reconhecê-la sem ler a
 * mensagem do provedor: esgotadas as tentativas, o limite de taxa é falha desta pergunta e não
 * abre o disjuntor do modo fallback, que vale para todos os produtores.
 */
public class LimiteTaxaProvedorException extends TransientAiException {

  /**
   * Cria a exceção.
   *
   * @param mensagem mensagem técnica montada pelo Spring AI (status e corpo da resposta); só vai ao
   *                 log em DEBUG, nunca ao cliente
   */
  public LimiteTaxaProvedorException(String mensagem) {
    super(mensagem);
  }
}
