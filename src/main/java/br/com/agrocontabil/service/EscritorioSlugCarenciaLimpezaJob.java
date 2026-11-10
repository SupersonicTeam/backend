package br.com.agrocontabil.service;

import br.com.agrocontabil.repository.EscritorioSlugAnteriorRepository;
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
 * Apaga os slugs cuja carência acabou (issue #464). O slug que sai de um escritório fica bloqueado
 * por 90 dias e depois some: sem a limpeza, um endereço com o nome de uma pessoa ficaria guardado
 * para sempre, além do que a política de dados declara (LGPD art. 15).
 */
@Component
@ConditionalOnProperty(name = "app.escritorio.endereco.limpeza.enabled", havingValue = "true",
    matchIfMissing = true)
public class EscritorioSlugCarenciaLimpezaJob {

  private static final Logger log =
      LoggerFactory.getLogger(EscritorioSlugCarenciaLimpezaJob.class);

  /** Nome do lock distribuído: a limpeza roda numa réplica só. */
  public static final String LOCK = "escritorio-slug-carencia-limpeza";

  private final EscritorioSlugAnteriorRepository anteriorRepository;
  private final Clock clock;

  public EscritorioSlugCarenciaLimpezaJob(EscritorioSlugAnteriorRepository anteriorRepository,
      Clock clock) {
    this.anteriorRepository = anteriorRepository;
    this.clock = clock;
  }

  /** Todo dia às 3h20 ({@code app.escritorio.endereco.limpeza.cron}). */
  @Scheduled(cron = "${app.escritorio.endereco.limpeza.cron:0 20 3 * * *}",
      zone = "America/Sao_Paulo")
  @SchedulerLock(
      name = LOCK,
      lockAtMostFor = "${app.escritorio.endereco.limpeza.lock.at-most-for:PT30M}",
      lockAtLeastFor = "${app.escritorio.endereco.limpeza.lock.at-least-for:PT5M}")
  @Transactional
  public void limpar() {
    int apagados = anteriorRepository.apagarVencidos(OffsetDateTime.now(clock));
    if (apagados > 0) {
      log.info("Limpeza da carencia dos enderecos de escritorio: {} slug(s) liberado(s)",
          apagados);
    }
  }
}
