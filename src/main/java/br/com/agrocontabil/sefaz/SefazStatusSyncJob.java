package br.com.agrocontabil.sefaz;

import br.com.agrocontabil.service.SefazService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
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
 * <p>Antes da sincronização, resgata notas presas em
 * {@link br.com.agrocontabil.model.StatusNotaFiscal#ENVIANDO} ou
 * {@code CANCELANDO} há mais que {@code app.nfe.sefaz.transmissao.timeout-ms} (issue #96) — o
 * caso de o processo cair no meio de uma transmissão, que sem isso deixaria a nota inoperável.
 *
 * <p>Ativo somente quando {@code app.nfe.sefaz.sync.enabled=true} (padrão). Desabilitado
 * no perfil de testes para não interferir nas asserções.
 */
@Component
@ConditionalOnProperty(name = "app.nfe.sefaz.sync.enabled", havingValue = "true",
    matchIfMissing = true)
public class SefazStatusSyncJob {

  private static final Logger log = LoggerFactory.getLogger(SefazStatusSyncJob.class);

  /**
   * Nome do lock distribuído deste job (issue #87). Com 2+ instâncias só uma sincroniza por vez.
   *
   * <p>Teto de 14 min (abaixo do intervalo de 15) e piso de 10 min: como cada réplica agenda o
   * próprio {@code fixedDelay}, sem o piso a outra réplica rodaria de novo logo depois de esta
   * terminar, e a SEFAZ seria consultada a cada poucos minutos em vez de a cada ~15.
   */
  public static final String LOCK = "sefaz-status-sync";

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
  @SchedulerLock(
      name = LOCK,
      lockAtMostFor = "${app.nfe.sefaz.sync.lock.at-most-for:PT14M}",
      lockAtLeastFor = "${app.nfe.sefaz.sync.lock.at-least-for:PT10M}")
  public void sincronizarPendentes() {
    log.debug("Iniciando sincronizacao agendada de status de NF-e pendentes com a SEFAZ");

    // issue #96: primeiro resgata notas presas em ENVIANDO/CANCELANDO (processo derrubado no meio
    // da transmissao) — as que viram PENDENTE_SEFAZ ja entram na sincronizacao logo abaixo,
    // fechando o ciclo na mesma execucao.
    int reconciliadas = sefazService.reconciliarTransmissoesPresas();
    if (reconciliadas > 0) {
      log.warn("Transmissoes presas reconciliadas: {} nota(s)", reconciliadas);
    }

    int atualizadas = sefazService.sincronizarPendentes();
    if (atualizadas > 0) {
      log.info("Sincronizacao SEFAZ concluida: {} nota(s) tiveram o status atualizado",
          atualizadas);
    }
  }
}
