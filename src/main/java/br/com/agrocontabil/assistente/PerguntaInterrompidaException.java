package br.com.agrocontabil.assistente;

/**
 * A pergunta foi interrompida (tempo total esgotado ou cliente do streaming desconectado) e a rota
 * parou antes da próxima chamada ao modelo ou às funções (issue #157).
 *
 * <p>Não chega ao cliente: quem interrompeu já respondeu (503 por tempo esgotado) ou o cliente já
 * foi embora. Serve para a thread da Ceres parar e registrar o desfecho na auditoria.
 */
public class PerguntaInterrompidaException extends RuntimeException {

  /** Cria a exceção, sem stack trace: é controle de fluxo, não defeito. */
  public PerguntaInterrompidaException() {
    super("Pergunta a Ceres interrompida", null, false, false);
  }
}
