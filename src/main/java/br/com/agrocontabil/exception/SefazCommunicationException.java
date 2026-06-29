package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * Lançada quando a comunicação com a SEFAZ falha por reason técnico (timeout,
 * conexão recusada, error de I/O, resposta inesperada). HTTP 502 Bad Gateway.
 *
 * <p>Esta exceção aciona o mecanismo de retry automático configurado em
 * {@link br.com.agrocontabil.service.SefazService} via {@code @Retryable}.
 * Após esgotar as tentativas, o {@code @Recover} a relança para o cliente.
 *
 * <p><strong>Nota:</strong> rejeição da SEFAZ por dados inválidos (cStat 2xx/4xx) NÃO é
 * exceção — é result válido persistido com {@link br.com.agrocontabil.model.InvoiceStatus#REJEITADA}.
 */
public class SefazCommunicationException extends DomainException {

  /**
   * Cria a exceção com a causa técnica encadeada.
   *
   * @param mensagemTecnica detalhe técnico (vai para o log, nunca ao cliente)
   * @param cause           causa original (I/O, timeout etc.)
   */
  public SefazCommunicationException(String mensagemTecnica, Throwable cause) {
    super(
        HttpStatus.BAD_GATEWAY,
        mensagemTecnica,
        List.of(ErrorDetail.of(ErrorCode.SEFAZ_COMUNICACAO_FALHOU,
            "Falha de comunicacao com a SEFAZ. Tente novamente em instantes.")));
    if (cause != null) {
      initCause(cause);
    }
  }

  /**
   * Cria a exceção sem causa encadeada (para testes ou cenários sem exception original).
   *
   * @param mensagemTecnica detalhe técnico (vai para o log, nunca ao cliente)
   */
  public SefazCommunicationException(String mensagemTecnica) {
    this(mensagemTecnica, null);
  }
}
