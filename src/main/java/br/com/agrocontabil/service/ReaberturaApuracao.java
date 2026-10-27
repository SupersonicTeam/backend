package br.com.agrocontabil.service;

import br.com.agrocontabil.dto.ApuracaoResponse;
import br.com.agrocontabil.dto.ResultadoAuditavel;
import java.util.UUID;

/**
 * Resultado da reabertura pelo contador (issue #224): a resposta da API e, só para a auditoria, o
 * id da apuração e o produtor dono.
 *
 * <p>O id não vai ao cliente — a rota é pelo período e o {@code ApuracaoResponse} segue sem
 * {@code id} —, mas o registro de auditoria precisa dele, e o contador só acha o registro pelo
 * produtor.
 *
 * @param apuracao   a apuração reaberta, como a API devolve
 * @param apuracaoId id da apuração reaberta
 * @param produtorId produtor dono da apuração
 */
public record ReaberturaApuracao(ApuracaoResponse apuracao, UUID apuracaoId, UUID produtorId)
    implements ResultadoAuditavel {

  @Override
  public Object status() {
    return apuracao.status();
  }

  @Override
  public UUID recursoAuditado() {
    return apuracaoId;
  }

  @Override
  public UUID produtorAuditado() {
    return produtorId;
  }
}
