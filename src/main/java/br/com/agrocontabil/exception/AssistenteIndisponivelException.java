package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * O modelo de linguagem da Ceres não respondeu: Ollama fora do ar, provedor por API recusou a
 * chamada, timeout, nenhum modelo configurado (issue #141). HTTP 503 Service Unavailable.
 *
 * <p>A mensagem técnica (provedor, tipo da falha) vai só para o log. O cliente recebe
 * {@link #MENSAGEM_CLIENTE}: nem o nome do provedor nem o erro dele saem da API — a mensagem de
 * erro de um provedor por API pode conter trechos da requisição.
 */
public class AssistenteIndisponivelException extends DomainException {

  /** Mensagem segura para o cliente da API. */
  public static final String MENSAGEM_CLIENTE =
      "A assistente Ceres está indisponível no momento. Tente novamente em instantes.";

  /**
   * Cria a exceção com a causa técnica encadeada.
   *
   * @param mensagemTecnica detalhe técnico (vai para o log, nunca ao cliente); sem conteúdo da
   *                        pergunta nem dados do produtor
   * @param cause           causa original; pode ser nula
   */
  public AssistenteIndisponivelException(String mensagemTecnica, Throwable cause) {
    super(
        HttpStatus.SERVICE_UNAVAILABLE,
        mensagemTecnica,
        List.of(ErrorDetail.of(ErrorCode.ASSISTENTE_INDISPONIVEL, MENSAGEM_CLIENTE)));
    if (cause != null) {
      initCause(cause);
    }
  }

}
