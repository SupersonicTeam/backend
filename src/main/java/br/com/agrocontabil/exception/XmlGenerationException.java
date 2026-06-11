package br.com.agrocontabil.exception;

/**
 * Lançada quando ocorre um erro técnico durante a geração ou validação do XML da NF-e.
 *
 * <p>Cobre falhas de marshalling JAXB e falhas de validação XSD.
 * Mapeada para HTTP 422 pelo {@code GlobalExceptionHandler}.
 */
public class XmlGenerationException extends RuntimeException {

  /**
   * Cria a exceção com mensagem de erro amigável.
   *
   * @param message descrição do problema
   */
  public XmlGenerationException(String message) {
    super(message);
  }

  /**
   * Cria a exceção com mensagem e causa raiz.
   *
   * @param message descrição do problema
   * @param cause   exceção original (não exposta ao cliente)
   */
  public XmlGenerationException(String message, Throwable cause) {
    super(message, cause);
  }
}
