package br.com.agrocontabil.exception;

/**
 * Lançada quando um XML de invoice fiscal não pode ser importado: malformado, sem
 * elemento {@code <NFe>}, inválido contra o XSD, ou com campos obrigatórios ausentes.
 *
 * <p>No fluxo de importação em lote, é capturada por fileName e registrada como error
 * no relatório — não chega ao {@code GlobalExceptionHandler}.
 */
public class XmlImportException extends RuntimeException {

  public XmlImportException(String message) {
    super(message);
  }

  public XmlImportException(String message, Throwable cause) {
    super(message, cause);
  }
}
