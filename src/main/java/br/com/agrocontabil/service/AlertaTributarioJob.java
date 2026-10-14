package br.com.agrocontabil.service;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Job diário dos alertas tributários (issue #140, RN010), mesmo padrão do
 * {@code GuiaFiscalStatusSyncJob}: cron de 6 campos, fuso de Brasília e lock distribuído.
 *
 * <p>Ativo somente quando {@code app.alertas.enabled=true} (padrão). Desabilitado no perfil de
 * testes para não interferir nas asserções.
 */
@Component
@ConditionalOnProperty(name = "app.alertas.enabled", havingValue = "true", matchIfMissing = true)
public class AlertaTributarioJob {

  /**
   * Nome do lock distribuído (issue #87): os alertas são gerados numa réplica só. Piso de 5 min
   * pelo desvio de relógio entre réplicas que disparam o mesmo cron; o lock envolve o
   * {@code @Transactional} do service (ver {@code SchedulingConfig}).
   */
  public static final String LOCK = "alertas-tributarios";

  private final AlertaTributarioService service;

  public AlertaTributarioJob(AlertaTributarioService service) {
    this.service = service;
  }

  /** Roda às 9h ({@code app.alertas.cron}, padrão {@code "0 0 9 * * *"} — 6 campos). */
  @Scheduled(cron = "${app.alertas.cron:0 0 9 * * *}", zone = "America/Sao_Paulo")
  @SchedulerLock(
      name = LOCK,
      lockAtMostFor = "${app.alertas.lock.at-most-for:PT30M}",
      lockAtLeastFor = "${app.alertas.lock.at-least-for:PT5M}")
  public void gerar() {
    service.gerarAlertas();
  }
}
