package br.com.agrocontabil.assistente.ferramentas;

/**
 * O modelo chamou uma função da Ceres com parâmetro inválido (issue #142).
 *
 * <p>A mensagem é escrita por nós e volta ao modelo, para ele corrigir a chamada — por isso nunca
 * contém dado do produtor nem detalhe técnico. Não é {@code DomainException}: nunca chega ao
 * cliente HTTP.
 */
public class ParametroFerramentaInvalidoException extends RuntimeException {

  /**
   * Cria a exceção.
   *
   * @param mensagem orientação ao modelo sobre como corrigir a chamada
   */
  public ParametroFerramentaInvalidoException(String mensagem) {
    super(mensagem);
  }
}
