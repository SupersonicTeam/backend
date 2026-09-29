package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * A Ceres já está processando o máximo de perguntas simultâneas (issue #142). HTTP 429.
 *
 * <p>Cada pergunta prende uma thread do Tomcat pelo tempo de geração do modelo. O teto
 * ({@code app.assistente.max-conversas-simultaneas}) impede que o chat esgote as threads do
 * servidor inteiro — e com elas as notas, a SEFAZ e a apuração.
 */
public class AssistenteOcupadoException extends DomainException {

  /** Cria a exceção com a mensagem padrão. */
  public AssistenteOcupadoException() {
    super(
        HttpStatus.TOO_MANY_REQUESTS,
        "Limite de perguntas simultaneas da Ceres atingido",
        List.of(ErrorDetail.of(ErrorCode.ASSISTENTE_OCUPADO,
            "A Ceres esta atendendo muitas perguntas agora. Tente novamente em instantes.")));
  }
}
