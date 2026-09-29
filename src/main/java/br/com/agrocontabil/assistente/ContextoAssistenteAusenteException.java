package br.com.agrocontabil.assistente;

/**
 * Uma função da Ceres foi chamada sem {@link ContextoAssistente} no {@code ToolContext} (issue
 * #143). É defeito do backend, nunca do usuário: a pergunta é abortada (500 genérico) em vez de a
 * função consultar sem saber de quem são os dados.
 */
public class ContextoAssistenteAusenteException extends RuntimeException {

  /** Cria a exceção. */
  public ContextoAssistenteAusenteException() {
    super("Funcao da Ceres chamada sem ContextoAssistente no ToolContext");
  }
}
