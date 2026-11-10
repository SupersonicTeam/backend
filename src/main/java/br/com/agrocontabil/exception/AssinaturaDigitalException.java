package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * Falha na assinatura digital da NF-e (certificado ou processo de assinatura).
 *
 * <p>São erros de configuração do servidor ou bug interno — não corrigíveis pelo
 * usuário final — portanto HTTP 500. O detalhe técnico fica só no log; o cliente
 * recebe uma mensagem genérica e o {@code code} estruturado.
 */
public class AssinaturaDigitalException extends DomainException {

  private static final String CLIENT_MESSAGE =
      "Não foi possível assinar digitalmente a NF-e. Contate o suporte.";

  public AssinaturaDigitalException(ErrorCode code, String technicalMessage) {
    super(HttpStatus.INTERNAL_SERVER_ERROR, technicalMessage,
        List.of(ErrorDetail.of(code, CLIENT_MESSAGE)));
  }

  public AssinaturaDigitalException(ErrorCode code, String technicalMessage, Throwable cause) {
    this(code, technicalMessage);
    initCause(cause);
  }
}
