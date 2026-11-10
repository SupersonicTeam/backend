package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * Erro técnico na geração/validação do XML da NF-e (marshalling JAXB ou XSD). HTTP 422.
 *
 * <p>A mensagem técnica ({@link #getMessage()}) só vai para o log (via handler). Ao cliente
 * é devolvido um envelope genérico, sem vazar fragmento de XML ou contexto do XSD.
 */
public class XmlGenerationException extends DomainException implements ComMensagemCliente {

  private static final String CLIENT_MESSAGE =
      "Não foi possível gerar o XML da NF-e. "
          + "Verifique os dados cadastrais do emitente e do destinatário.";

  public XmlGenerationException(String technicalMessage) {
    super(HttpStatus.UNPROCESSABLE_ENTITY, technicalMessage,
        List.of(ErrorDetail.of(ErrorCode.XML_GENERATION_ERROR, CLIENT_MESSAGE)));
  }

  public XmlGenerationException(String technicalMessage, Throwable cause) {
    this(technicalMessage);
    initCause(cause);
  }

  /** Mensagem genérica e segura para exibir ao cliente. */
  @Override
  public String clientMessage() {
    return CLIENT_MESSAGE;
  }
}
