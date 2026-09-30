package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * A pergunta à Ceres passou de {@code app.assistente.timeout-pergunta} e foi interrompida (issue
 * #157). HTTP 503 com {@code ASSISTENTE_TEMPO_ESGOTADO}.
 *
 * <p>O teto existe para que uma pergunta lenta (modelo local em CPU, várias rodadas de funções) não
 * prenda uma vaga da Ceres indefinidamente.
 */
public class AssistenteTempoEsgotadoException extends DomainException {

  /** Cria a exceção com a mensagem padrão. */
  public AssistenteTempoEsgotadoException() {
    super(HttpStatus.SERVICE_UNAVAILABLE, "Pergunta a Ceres passou do tempo maximo",
        List.of(ErrorDetail.of(ErrorCode.ASSISTENTE_TEMPO_ESGOTADO,
            "A Ceres demorou demais para responder. Tente uma pergunta mais especifica ou tente "
                + "novamente em instantes.")));
  }
}
