package br.com.agrocontabil.service;

import br.com.agrocontabil.repository.NotificacaoRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Apaga as notificações com mais de {@code app.notificacao.retencao-dias} (issue #182, padrão
 * 365): não são documento fiscal, e o texto pode ter dado pessoal (LGPD art. 15).
 */
@Component
@ConditionalOnProperty(name = "app.notificacao.retencao.enabled", havingValue = "true",
    matchIfMissing = true)
public class NotificacaoRetencaoJob {

  private static final Logger log = LoggerFactory.getLogger(NotificacaoRetencaoJob.class);

  /** Nome do lock distribuído. */
  public static final String LOCK = "notificacao-retencao";

  private final NotificacaoRepository repository;
  private final int retencaoDias;
  private final Clock clock;

  public NotificacaoRetencaoJob(NotificacaoRepository repository,
      @Value("${app.notificacao.retencao-dias:365}") int retencaoDias, Clock clock) {
    if (retencaoDias < 1) {
      throw new IllegalStateException("app.notificacao.retencao-dias precisa ser >= 1.");
    }
    this.repository = repository;
    this.retencaoDias = retencaoDias;
    this.clock = clock;
  }

  /** Todo dia às 4h ({@code app.notificacao.retencao.cron}). */
  @Scheduled(cron = "${app.notificacao.retencao.cron:0 0 4 * * *}", zone = "America/Sao_Paulo")
  @SchedulerLock(
      name = LOCK,
      lockAtMostFor = "${app.notificacao.retencao.lock.at-most-for:PT30M}",
      lockAtLeastFor = "${app.notificacao.retencao.lock.at-least-for:PT5M}")
  @Transactional
  public void aplicar() {
    OffsetDateTime corte = OffsetDateTime.now(clock).minusDays(retencaoDias);
    int apagadas = repository.apagarAnterioresA(corte);
    if (apagadas > 0) {
      log.info("Retencao das notificacoes: {} apagada(s), criadas antes de {}", apagadas, corte);
    }
  }
}
