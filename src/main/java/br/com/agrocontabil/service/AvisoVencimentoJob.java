package br.com.agrocontabil.service;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Aviso urgente automático (issue #182): todo dia às 9h registra uma notificação {@code URGENTE}
 * por item que vence amanhã, para todo produtor com WhatsApp, sem depender do botão. Idempotente
 * por item e data. Não é {@code @Transactional}: cada produtor tem a transação dele.
 */
@Component
@ConditionalOnProperty(name = "app.notificacao.aviso.enabled", havingValue = "true",
    matchIfMissing = true)
public class AvisoVencimentoJob {

  private static final Logger log = LoggerFactory.getLogger(AvisoVencimentoJob.class);

  /** Nome do lock distribuído: o aviso é gerado numa réplica só. */
  public static final String LOCK = "notificacao-aviso-vencimento";

  private final NotificacaoService notificacaoService;

  public AvisoVencimentoJob(NotificacaoService notificacaoService) {
    this.notificacaoService = notificacaoService;
  }

  /** Às 9h ({@code app.notificacao.aviso.cron}), como os alertas tributários. */
  @Scheduled(cron = "${app.notificacao.aviso.cron:0 0 9 * * *}", zone = "America/Sao_Paulo")
  @SchedulerLock(
      name = LOCK,
      lockAtMostFor = "${app.notificacao.aviso.lock.at-most-for:PT30M}",
      lockAtLeastFor = "${app.notificacao.aviso.lock.at-least-for:PT5M}")
  public void avisar() {
    int registrados = notificacaoService.avisarVencimentosDeAmanha();
    if (registrados > 0) {
      log.info("Aviso automatico de vencimento: {} notificacao(oes) registrada(s)", registrados);
    }
  }
}
