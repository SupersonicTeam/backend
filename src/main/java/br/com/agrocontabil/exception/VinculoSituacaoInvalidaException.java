package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import br.com.agrocontabil.model.SituacaoVinculo;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * Transição de vínculo que a situação atual não permite (issue #177) — HTTP 409.
 *
 * <p>Ex.: suspender um vínculo já suspenso ou reativar um ativo. Encerrado não chega aqui: para o
 * escritório, vínculo encerrado não existe mais (404).
 */
public class VinculoSituacaoInvalidaException extends DomainException {

  /**
   * Cria a exceção.
   *
   * @param atual    situação atual do vínculo
   * @param exigidas situações a partir das quais a transição vale
   */
  public VinculoSituacaoInvalidaException(SituacaoVinculo atual, List<SituacaoVinculo> exigidas) {
    super(HttpStatus.CONFLICT, mensagem(atual), List.of(new ErrorDetail(
        ErrorCode.VINCULO_SITUACAO_INVALIDA.code(),
        "situacao",
        mensagem(atual),
        "Recarregue a carteira: a situacao do vinculo mudou ou nao permite esta operacao.",
        atual.name(),
        exigidas.toString())));
  }

  private static String mensagem(SituacaoVinculo atual) {
    return "O vinculo esta " + atual.name() + " e nao permite esta operacao.";
  }
}
