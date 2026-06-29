package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * Violação de uma regra de negócio que impede a conclusão da operação, embora a
 * requisição esteja sintaticamente válida.
 *
 * <p>Mapeada para HTTP 422 (Unprocessable Entity) via {@link DomainException}.
 * A mensagem é descritiva e segura para o cliente. Usada, por exemplo, pela regra
 * dos 30 dias do cancelamento de NF-e (RN006).
 */
public class BusinessException extends DomainException {

  public BusinessException(String message) {
    super(
        HttpStatus.UNPROCESSABLE_ENTITY,
        message,
        List.of(ErrorDetail.of(ErrorCode.REGRA_NEGOCIO, message)));
  }
}
