package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * Lançada quando a comunicação com a SEFAZ falha por motivo técnico (timeout,
 * conexão recusada, erro de I/O, resposta inesperada). HTTP 502 Bad Gateway.
 *
 * <p>Base da hierarquia de falhas de comunicação (issue #83). O transporte deve lançar uma das
 * subclasses, porque é a distinção entre elas que decide se reenviar é seguro:
 * <ul>
 *   <li>{@link SefazConexaoException}: a requisição comprovadamente <strong>não</strong> chegou ao
 *       fisco. Reenviar é seguro.</li>
 *   <li>{@link SefazRespostaIndeterminadaException}: a requisição <strong>pode</strong> ter sido
 *       processada. Reenviar às cegas duplicaria a NF-e (cStat 204) ou o evento (cStat 573); o
 *       desfecho é descoberto consultando a SEFAZ pela chave.</li>
 * </ul>
 * Uma instância direta desta classe (transporte que não sabe classificar a falha) é tratada como
 * <strong>indeterminada</strong>: na dúvida, nunca reenviar. A política de retry por operação está
 * em {@link br.com.agrocontabil.sefaz.SefazClient}.
 *
 * <p><strong>Nota:</strong> rejeição da SEFAZ por dados inválidos (cStat 2xx/4xx) NÃO é
 * exceção — é resultado válido persistido com {@link br.com.agrocontabil.model.StatusNotaFiscal#REJEITADA}.
 */
public class SefazComunicacaoException extends DomainException {

  /**
   * Cria a exceção com a causa técnica encadeada.
   *
   * @param mensagemTecnica detalhe técnico (vai para o log, nunca ao cliente)
   * @param cause           causa original (I/O, timeout etc.)
   */
  public SefazComunicacaoException(String mensagemTecnica, Throwable cause) {
    this(mensagemTecnica, cause,
        "Falha de comunicacao com a SEFAZ. Tente novamente em instantes.");
  }

  /**
   * Construtor das subclasses, que ajustam a mensagem ao que o cliente pode fazer em seguida.
   *
   * @param mensagemTecnica detalhe técnico (vai para o log, nunca ao cliente)
   * @param cause           causa original; pode ser nula
   * @param mensagemCliente mensagem segura para o cliente da API
   */
  protected SefazComunicacaoException(
      String mensagemTecnica, Throwable cause, String mensagemCliente) {
    super(
        HttpStatus.BAD_GATEWAY,
        mensagemTecnica,
        List.of(ErrorDetail.of(ErrorCode.SEFAZ_COMUNICACAO_FALHOU, mensagemCliente)));
    if (cause != null) {
      initCause(cause);
    }
  }

  /**
   * Cria a exceção sem causa encadeada (para testes ou cenários sem exception original).
   *
   * @param mensagemTecnica detalhe técnico (vai para o log, nunca ao cliente)
   */
  public SefazComunicacaoException(String mensagemTecnica) {
    this(mensagemTecnica, null);
  }
}
