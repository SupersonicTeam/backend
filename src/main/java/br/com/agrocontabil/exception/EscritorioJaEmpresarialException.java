package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * O escritório já é empresarial (issue #519) — HTTP 409, no {@code tornar-empresarial}. Os dados
 * do escritório empresarial mudam pelo {@code PATCH /api/escritorio}.
 */
public class EscritorioJaEmpresarialException extends DomainException {

  private static final String MENSAGEM = "O escritório já é empresarial.";

  /** Cria a exceção. */
  public EscritorioJaEmpresarialException() {
    super(HttpStatus.CONFLICT, MENSAGEM, List.of(new ErrorDetail(
        ErrorCode.ESCRITORIO_JA_EMPRESARIAL.code(),
        null,
        MENSAGEM,
        "Para mudar o nome, o CNPJ, o telefone ou o endereço, use o PATCH /api/escritorio.",
        null,
        null)));
  }
}
