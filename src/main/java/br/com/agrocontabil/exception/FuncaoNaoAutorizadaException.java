package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import br.com.agrocontabil.model.FuncaoEscritorio;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * A função do contador no escritório não faz a operação, ou ele não é membro de escritório
 * nenhum (issue #228) — HTTP 403 {@code PAPEL_NAO_AUTORIZADO}, como a negativa por papel: o que
 * falta é permissão, não um recurso de terceiro.
 */
public class FuncaoNaoAutorizadaException extends DomainException {

  /** Valor de {@code rejectedValue} do contador que não pertence a escritório nenhum. */
  public static final String SEM_ESCRITORIO = "SEM_ESCRITORIO";

  /**
   * Cria a exceção.
   *
   * @param atual      função atual ({@code null} = sem escritório)
   * @param permitidas funções que fazem a operação
   */
  public FuncaoNaoAutorizadaException(FuncaoEscritorio atual, List<FuncaoEscritorio> permitidas) {
    super(HttpStatus.FORBIDDEN, mensagem(atual), List.of(new ErrorDetail(
        ErrorCode.PAPEL_NAO_AUTORIZADO.code(),
        null,
        mensagem(atual),
        atual == null
            ? "Entre num escritorio (aceite um convite de equipe) para atender clientes."
            : "Peca a um administrador do escritorio para fazer esta operacao.",
        atual == null ? SEM_ESCRITORIO : atual.name(),
        permitidas.toString())));
  }

  private static String mensagem(FuncaoEscritorio atual) {
    return atual == null
        ? "Contador sem escritorio."
        : "Operacao nao permitida para a funcao " + atual.name() + " no escritorio.";
  }
}
