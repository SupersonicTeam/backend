package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * O endereço do escritório foi alterado há pouco (issue #464): uma gravação de slug a cada
 * intervalo (30 dias) — HTTP 409. O {@code expectedValue} traz o instante em que a troca libera.
 */
public class EnderecoTrocaRecenteException extends DomainException {

  private static final String MENSAGEM =
      "O endereço do escritório foi alterado há pouco e só pode mudar de novo depois do prazo.";

  /**
   * Cria a exceção.
   *
   * @param liberaEm instante a partir do qual o endereço pode ser gravado de novo
   */
  public EnderecoTrocaRecenteException(OffsetDateTime liberaEm) {
    super(HttpStatus.CONFLICT, MENSAGEM, List.of(new ErrorDetail(
        ErrorCode.ENDERECO_TROCA_RECENTE.code(),
        "slug",
        MENSAGEM,
        "Tente de novo a partir da data indicada. Remover o endereço continua permitido.",
        null,
        liberaEm.toInstant().toString())));
  }
}
