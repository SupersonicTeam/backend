package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * Cadastro sem {@code aceiteTermos} quando o aceite é exigido ({@code app.cadastro.
 * exigir-aceite-termos}, issue #519) — HTTP 400 {@code VALIDATION_ERROR} no campo, como as
 * validações do request. O {@code false} já é recusado pelo {@code @AssertTrue} do DTO; a ausência
 * depende da configuração, por isso a regra fica no service.
 */
public class AceiteTermosObrigatorioException extends DomainException {

  /** Texto único do aceite: também é a mensagem do {@code @AssertTrue} dos dois cadastros. */
  public static final String MENSAGEM = "Para criar a conta, aceite os termos de uso.";

  /** Cria a exceção. */
  public AceiteTermosObrigatorioException() {
    super(HttpStatus.BAD_REQUEST, MENSAGEM, List.of(new ErrorDetail(
        ErrorCode.VALIDATION_ERROR.code(), "aceiteTermos", MENSAGEM, null, null, null)));
  }
}
