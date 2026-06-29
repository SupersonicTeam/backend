package br.com.agrocontabil.sefaz;

import br.com.agrocontabil.service.SefazService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Job agendado que sincroniza o status das NF-e pendentes com a SEFAZ (issue #16 — RF013).
 *
 * <p>Periodicamente (intervalo configurável em {@code app.nfe.sefaz.sync.interval-ms},
 * padrão 15 min) consulta a SEFAZ para todas as notas em
 * {@link br.com.agrocontabil.model.StatusNotaFiscal#PENDENTE_SEFAZ} e atualiza o status
 * local conforme o retorno.
 *
 * <p>Ativo somente quando {@code app.nfe.sefaz.sync.enabled=true} (padrão). Desabilitado
 * no perfil de testes para não interferir nas asserções.
 */
@Component
@ConditionalOnProperty(name = "app.nfe.sefaz.sync.enabled", havingValue = "true",
    matchIfMissing = true)
public class SefazStatusSyncJob {

  private static final Logger log = LoggerFactory.getLogger(SefazStatusSyncJob.class);

  private final SefazService sefazService;

  /** Constrói o job com o serviço de transmissão/consulta SEFAZ. */
  public SefazStatusSyncJob(SefazService sefazService) {
    this.sefazService = sefazService;
  }

  /**
   * Sincroniza o status das notas pendentes com a SEFAZ.
   *
   * <p>O intervalo é controlado por {@code app.nfe.sefaz.sync.interval-ms} (padrão 900000 ms
   * = 15 min). O primeiro disparo respeita o mesmo intervalo como atraso inicial, evitando
   * execução logo no startup.
   */
  @Scheduled(
      fixedDelayString = "${app.nfe.sefaz.sync.interval-ms:900000}",
      initialDelayString = "${app.nfe.sefaz.sync.interval-ms:900000}")
  public void sincronizarPendentes() {
    log.debug("Iniciando sincronizacao agendada de status de NF-e pendentes com a SEFAZ");
    int atualizadas = sefazService.sincronizarPendentes();
    if (atualizadas > 0) {
      log.info("Sincronizacao SEFAZ concluida: {} nota(s) tiveram o status atualizado",
          atualizadas);
    }
  }
}
