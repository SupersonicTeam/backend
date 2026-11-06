package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * A Ceres não tem vaga para esta pergunta (issues #142 e #157). HTTP 503 com {@code Retry-After}.
 *
 * <p>Duas situações, o mesmo código {@code ASSISTENTE_OCUPADO}:
 * <ul>
 *   <li>o limite global ({@code app.assistente.max-conversas-simultaneas}) foi atingido — cada
 *       pergunta ocupa uma thread do pool da Ceres pelo tempo de geração do modelo;</li>
 *   <li>o produtor já tem uma pergunta em andamento: um único usuário não ocupa todas as vagas.</li>
 * </ul>
 *
 * <p>503, e não 429: o limite é da capacidade do servidor, não uma cota do cliente. O
 * {@code Retry-After} diz em quantos segundos vale tentar de novo; o
 * {@link GlobalExceptionHandler} o põe no header.
 */
public class AssistenteOcupadoException extends DomainException implements ComRetryAfter {

  /** Segundos sugeridos no header {@code Retry-After}. */
  public static final int RETRY_AFTER_SEGUNDOS = 5;

  private AssistenteOcupadoException(String mensagemLog, String mensagemCliente) {
    super(HttpStatus.SERVICE_UNAVAILABLE, mensagemLog,
        List.of(ErrorDetail.of(ErrorCode.ASSISTENTE_OCUPADO, mensagemCliente)));
  }

  /** Limite global de perguntas simultâneas atingido. */
  public static AssistenteOcupadoException limiteGlobal() {
    return new AssistenteOcupadoException(
        "Limite de perguntas simultaneas da Ceres atingido",
        "A Ceres está atendendo muitas perguntas agora. Tente novamente em instantes.");
  }

  /** O produtor já tem uma pergunta em andamento. */
  public static AssistenteOcupadoException perguntaEmAndamento() {
    return new AssistenteOcupadoException(
        "Produtor ja tem uma pergunta em andamento na Ceres",
        "Aguarde a resposta da pergunta anterior antes de enviar outra.");
  }

  /** Valor do header {@code Retry-After}, em segundos. */
  @Override
  public long retryAfterSegundos() {
    return RETRY_AFTER_SEGUNDOS;
  }
}
