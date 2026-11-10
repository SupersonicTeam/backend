package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * Lançada quando a geração do PDF da DANFE falha por motivo técnico (compilação do template,
 * preenchimento ou exportação do relatório). HTTP 500 Internal Server Error.
 *
 * <p>O detalhe técnico vai apenas para o log; ao cliente é exposta uma mensagem genérica.
 */
public class DanfeGenerationException extends DomainException {

  /**
   * Cria a exceção com a causa técnica encadeada.
   *
   * @param mensagemTecnica detalhe técnico (vai para o log, nunca ao cliente)
   * @param cause           causa original (JRException, IOException etc.)
   */
  public DanfeGenerationException(String mensagemTecnica, Throwable cause) {
    super(
        HttpStatus.INTERNAL_SERVER_ERROR,
        mensagemTecnica,
        List.of(ErrorDetail.of(ErrorCode.DANFE_GENERATION_ERROR,
            "Não foi possível gerar o PDF da DANFE. Tente novamente em instantes.")));
    if (cause != null) {
      initCause(cause);
    }
  }
}
