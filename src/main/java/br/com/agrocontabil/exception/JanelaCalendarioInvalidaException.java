package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * Janela {@code de}/{@code ate} do calendário fiscal inválida (issue #103). HTTP 400.
 *
 * <p>Dois casos: início depois do fim, ou janela maior que o máximo permitido — o limite existe
 * para que o endpoint de vencimentos não volte a devolver o histórico inteiro do produtor.
 */
public class JanelaCalendarioInvalidaException extends DomainException {

  private JanelaCalendarioInvalidaException(String mensagem, ErrorDetail detalhe) {
    super(HttpStatus.BAD_REQUEST, mensagem, List.of(detalhe));
  }

  /**
   * Início da janela depois do fim.
   *
   * @param de  início informado (ou resolvido pelo padrão)
   * @param ate fim informado (ou resolvido pelo padrão)
   * @return exceção com o erro apontado para o campo {@code de}
   */
  public static JanelaCalendarioInvalidaException inicioDepoisDoFim(LocalDate de, LocalDate ate) {
    String mensagem = "Janela inválida: 'de' (%s) é posterior a 'ate' (%s).".formatted(de, ate);
    return new JanelaCalendarioInvalidaException(mensagem, new ErrorDetail(
        ErrorCode.JANELA_CALENDARIO_INVALIDA.code(),
        "de",
        mensagem,
        "Informe 'de' anterior ou igual a 'ate'.",
        de.toString(),
        "até " + ate));
  }

  /**
   * Janela maior que o máximo permitido.
   *
   * @param de          início da janela
   * @param ate         fim da janela
   * @param mesesMaximo tamanho máximo da janela, em meses
   * @return exceção com o erro apontado para o campo {@code ate}
   */
  public static JanelaCalendarioInvalidaException excedeMaximo(
      LocalDate de, LocalDate ate, int mesesMaximo) {
    LocalDate limite = de.plusMonths(mesesMaximo).minusDays(1);
    String mensagem = "Janela inválida: de %s a %s passa do máximo de %d meses."
        .formatted(de, ate, mesesMaximo);
    return new JanelaCalendarioInvalidaException(mensagem, new ErrorDetail(
        ErrorCode.JANELA_CALENDARIO_INVALIDA.code(),
        "ate",
        mensagem,
        "Consulte o calendário em janelas de até %d meses (informe 'de' e 'ate')."
            .formatted(mesesMaximo),
        ate.toString(),
        "até " + limite));
  }
}
