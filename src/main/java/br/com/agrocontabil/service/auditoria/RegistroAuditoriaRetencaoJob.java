package br.com.agrocontabil.service.auditoria;

import br.com.agrocontabil.config.AuditoriaProperties;
import br.com.agrocontabil.repository.RegistroAuditoriaRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Retenção da auditoria (issue #149, RNF012): apaga os registros com mais de
 * {@code app.auditoria.retencao-anos} (5, nunca menos — ver {@link AuditoriaProperties}).
 *
 * <p>Mesmo padrão dos demais jobs: desligável por propriedade (desligado nos testes) e com lock
 * distribuído ({@code SchedulerLockConfigTest} exige).
 */
@Component
@ConditionalOnProperty(name = "app.auditoria.retencao.enabled", havingValue = "true",
    matchIfMissing = true)
public class RegistroAuditoriaRetencaoJob {

  private static final Logger log = LoggerFactory.getLogger(RegistroAuditoriaRetencaoJob.class);

  /** Nome do lock distribuído: a retenção roda numa réplica só. */
  public static final String LOCK = "auditoria-retencao";

  private final RegistroAuditoriaRepository repository;
  private final AuditoriaProperties propriedades;
  private final Clock clock;

  public RegistroAuditoriaRetencaoJob(RegistroAuditoriaRepository repository,
      AuditoriaProperties propriedades, Clock clock) {
    this.repository = repository;
    this.propriedades = propriedades;
    this.clock = clock;
  }

  /** Todo dia às 3h30 ({@code app.auditoria.retencao.cron}). */
  @Scheduled(cron = "${app.auditoria.retencao.cron:0 30 3 * * *}", zone = "America/Sao_Paulo")
  @SchedulerLock(
      name = LOCK,
      lockAtMostFor = "${app.auditoria.retencao.lock.at-most-for:PT30M}",
      lockAtLeastFor = "${app.auditoria.retencao.lock.at-least-for:PT5M}")
  @Transactional
  public void aplicarRetencao() {
    OffsetDateTime corte = OffsetDateTime.now(clock).minusYears(propriedades.retencaoAnos());
    int apagados = repository.apagarAnterioresA(corte);
    if (apagados > 0) {
      log.info("Retencao da auditoria: {} registro(s) anteriores a {} apagado(s)", apagados, corte);
    }
  }
}
