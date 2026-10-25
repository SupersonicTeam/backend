package br.com.agrocontabil.service;

import br.com.agrocontabil.config.ConviteCarteiraProperties;
import br.com.agrocontabil.repository.ConviteCarteiraRepository;
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
 * Apaga os convites da carteira vencidos há mais de {@code app.carteira.convite.retencao} (issue
 * #177). O convite guarda o e-mail de quem talvez nunca tenha conta: sem limpeza, o dado pessoal
 * ficaria para sempre (LGPD art. 15).
 */
@Component
@ConditionalOnProperty(name = "app.carteira.convite.limpeza.enabled", havingValue = "true",
    matchIfMissing = true)
public class ConviteCarteiraLimpezaJob {

  private static final Logger log = LoggerFactory.getLogger(ConviteCarteiraLimpezaJob.class);

  /** Nome do lock distribuído: a limpeza roda numa réplica só. */
  public static final String LOCK = "convite-carteira-limpeza";

  private final ConviteCarteiraRepository conviteRepository;
  private final ConviteCarteiraProperties propriedades;
  private final Clock clock;

  public ConviteCarteiraLimpezaJob(ConviteCarteiraRepository conviteRepository,
      ConviteCarteiraProperties propriedades, Clock clock) {
    this.conviteRepository = conviteRepository;
    this.propriedades = propriedades;
    this.clock = clock;
  }

  /** Todo dia às 3h15 ({@code app.carteira.convite.limpeza.cron}). */
  @Scheduled(cron = "${app.carteira.convite.limpeza.cron:0 15 3 * * *}",
      zone = "America/Sao_Paulo")
  @SchedulerLock(
      name = LOCK,
      lockAtMostFor = "${app.carteira.convite.limpeza.lock.at-most-for:PT30M}",
      lockAtLeastFor = "${app.carteira.convite.limpeza.lock.at-least-for:PT5M}")
  @Transactional
  public void limpar() {
    OffsetDateTime corte = OffsetDateTime.now(clock).minus(propriedades.retencao());
    int apagados = conviteRepository.apagarExpiradosAntes(corte);
    if (apagados > 0) {
      log.info("Limpeza de convites da carteira: {} apagado(s), vencidos antes de {}", apagados,
          corte);
    }
  }
}
