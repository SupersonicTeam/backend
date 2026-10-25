package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import br.com.agrocontabil.model.StatusNotaFiscal;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/** Transição da revisão que o estado da nota não permite (issue #178) — HTTP 409. */
public class TransicaoRevisaoInvalidaException extends DomainException {

  private TransicaoRevisaoInvalidaException(String mensagem, String sugestao, String rejeitado,
      String esperado) {
    super(HttpStatus.CONFLICT, mensagem, List.of(new ErrorDetail(
        ErrorCode.TRANSICAO_REVISAO_INVALIDA.code(), "status", mensagem, sugestao, rejeitado,
        esperado)));
  }

  /**
   * O status atual não aceita a ação.
   *
   * @param notaId     nota
   * @param atual      status atual
   * @param permitidos status a partir dos quais a ação vale
   * @return a exceção
   */
  public static TransicaoRevisaoInvalidaException status(UUID notaId, StatusNotaFiscal atual,
      Collection<StatusNotaFiscal> permitidos) {
    return new TransicaoRevisaoInvalidaException(
        "A nota " + notaId + " esta em " + atual + " e nao permite esta acao.",
        "Recarregue a nota: o status mudou ou nao aceita esta acao.",
        atual.name(), permitidos.toString());
  }

  /**
   * O produtor não tem contador para revisar.
   *
   * @return a exceção
   */
  public static TransicaoRevisaoInvalidaException semContador() {
    return new TransicaoRevisaoInvalidaException(
        "Nenhum escritorio de contabilidade atende o produtor para revisar a nota.",
        "Sem contador vinculado, gere o XML direto.", null, null);
  }
}
