package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * Faixa de {@code GET /api/apuracao} inválida (issue #179). HTTP 400.
 *
 * <p>Cobre combinação de parâmetros que não faz sentido ({@code periodo} com faixa,
 * {@code ultimos} com {@code de}/{@code ate}), faixa invertida, faixa acima do teto e
 * {@code ultimos} fora dos valores aceitos.
 */
public class FaixaApuracaoInvalidaException extends DomainException {

  /**
   * Cria a exceção apontando o campo e o que se esperava dele.
   *
   * @param campo         parâmetro com problema
   * @param mensagem      o que está errado
   * @param sugestao      como corrigir
   * @param valor         valor recebido (pode ser nulo)
   * @param valorEsperado valor ou faixa aceita
   */
  public FaixaApuracaoInvalidaException(
      String campo, String mensagem, String sugestao, String valor, String valorEsperado) {
    super(HttpStatus.BAD_REQUEST, mensagem, List.of(new ErrorDetail(
        ErrorCode.FAIXA_APURACAO_INVALIDA.code(), campo, mensagem, sugestao, valor,
        valorEsperado)));
  }
}
