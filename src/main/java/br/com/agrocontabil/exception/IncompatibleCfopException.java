package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * Lançada quando o CFOP de um item é incompatível com o tipo de operação da invoice
 * (entrada/saída ou âmbito interno/interestadual).
 *
 * <p>Falha de validação do request de criação/atualização — mapeada para HTTP 400
 * via {@link DomainException}. A mensagem é descritiva e segura para o cliente
 * (não contém dado sensível).
 *
 * @see br.com.agrocontabil.service.CfopOperationValidator
 */
public class IncompatibleCfopException extends DomainException {

  public IncompatibleCfopException(String message) {
    super(
        HttpStatus.BAD_REQUEST,
        message,
        List.of(ErrorDetail.of(ErrorCode.CFOP_INCOMPATIVEL, message)));
  }
}
