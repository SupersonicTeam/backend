package br.com.agrocontabil.exception;

/**
 * Lançada quando um XML de nota fiscal não pode ser importado: malformado, sem
 * elemento {@code <NFe>}, inválido contra o XSD, ou com campos obrigatórios ausentes.
 *
 * <p>No fluxo de importação em lote, é capturada por arquivo e registrada como erro
 * no relatório — não chega ao {@code GlobalExceptionHandler}.
 */
public class XmlImportacaoException extends RuntimeException {

  public XmlImportacaoException(String message) {
    super(message);
  }

  public XmlImportacaoException(String message, Throwable cause) {
    super(message, cause);
  }
}
