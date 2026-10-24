package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * Filtro de {@code GET /api/notas} inválido (issue #174). HTTP 400.
 *
 * <p>Faixa invertida é erro, não página vazia: a página vazia faria o produtor achar que não tem
 * nota no período.
 */
public class FiltroNotasInvalidoException extends DomainException {

  private FiltroNotasInvalidoException(String mensagem, ErrorDetail detalhe) {
    super(HttpStatus.BAD_REQUEST, mensagem, List.of(detalhe));
  }

  /**
   * {@code emitidaDe} depois de {@code emitidaAte}.
   *
   * @param emitidaDe  início informado
   * @param emitidaAte fim informado
   * @return exceção com o erro apontado para o campo {@code emitidaDe}
   */
  public static FiltroNotasInvalidoException faixaInvertida(
      LocalDate emitidaDe, LocalDate emitidaAte) {
    String mensagem = "Faixa invalida: emitidaDe (%s) e posterior a emitidaAte (%s)."
        .formatted(emitidaDe, emitidaAte);
    return new FiltroNotasInvalidoException(mensagem, new ErrorDetail(
        ErrorCode.FILTRO_NOTAS_INVALIDO.code(),
        "emitidaDe",
        mensagem,
        "Informe emitidaDe anterior ou igual a emitidaAte.",
        emitidaDe.toString(),
        "ate " + emitidaAte));
  }

  /**
   * Ordenação por propriedade fora da lista permitida.
   *
   * @param propriedade propriedade pedida no {@code sort}
   * @param permitidas  propriedades aceitas
   * @return exceção com o erro apontado para o campo {@code sort}
   */
  public static FiltroNotasInvalidoException ordenacaoNaoPermitida(
      String propriedade, Collection<String> permitidas) {
    String lista = String.join(", ", permitidas);
    String mensagem = "Ordenacao nao permitida: %s.".formatted(propriedade);
    return new FiltroNotasInvalidoException(mensagem, new ErrorDetail(
        ErrorCode.FILTRO_NOTAS_INVALIDO.code(),
        "sort",
        mensagem,
        "Ordene por uma destas propriedades: " + lista + ".",
        propriedade,
        lista));
  }
}
