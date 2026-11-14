package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * Escritório empresarial sem CNPJ (issue #519) — HTTP 422, no {@code cnpj}.
 *
 * <p>Invariante do {@code Escritorio}, conferida só quando o tipo ou o CNPJ muda (como a do
 * {@code Produtor}, #298): um empresarial antigo sem CNPJ continua valendo. Quem grava confere no
 * service antes, porque no flush do commit o erro sairia 500.
 */
public class EmpresarialExigeCnpjException extends DomainException {

  private static final String MENSAGEM = "O escritório empresarial precisa do CNPJ.";

  /** Cria a exceção. */
  public EmpresarialExigeCnpjException() {
    super(HttpStatus.UNPROCESSABLE_ENTITY, MENSAGEM, List.of(new ErrorDetail(
        ErrorCode.ESCRITORIO_EMPRESARIAL_EXIGE_CNPJ.code(),
        "cnpj",
        MENSAGEM,
        "Informe o CNPJ do escritório (14 dígitos).",
        null,
        null)));
  }
}
