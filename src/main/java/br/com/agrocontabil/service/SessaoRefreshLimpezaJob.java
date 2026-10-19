package br.com.agrocontabil.service;

import br.com.agrocontabil.config.RefreshProperties;
import br.com.agrocontabil.repository.SessaoRefreshRepository;
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
 * Apaga as sessões de refresh vencidas há mais que a retenção (issue #207).
 *
 * <p>Sem isto a tabela cresceria uma linha por renovação para sempre. A retenção
 * ({@code app.seguranca.refresh.retencao}, 7 dias) mantém por um tempo as linhas recentes: são elas
 * que mostram, no banco, de que família veio um reuso detectado.
 *
 * <p>Mesmo padrão dos demais jobs: desligável por propriedade (desligado nos testes) e com lock
 * distribuído ({@code SchedulerLockConfigTest} exige).
 */
@Component
@ConditionalOnProperty(name = "app.seguranca.refresh.limpeza.enabled", havingValue = "true",
    matchIfMissing = true)
public class SessaoRefreshLimpezaJob {

  private static final Logger log = LoggerFactory.getLogger(SessaoRefreshLimpezaJob.class);

  /** Nome do lock distribuído: a limpeza roda numa réplica só. */
  public static final String LOCK = "sessao-refresh-limpeza";

  private final SessaoRefreshRepository sessaoRepository;
  private final RefreshProperties propriedades;
  private final Clock clock;

  public SessaoRefreshLimpezaJob(
      SessaoRefreshRepository sessaoRepository, RefreshProperties propriedades, Clock clock) {
    this.sessaoRepository = sessaoRepository;
    this.propriedades = propriedades;
    this.clock = clock;
  }

  /** Todo dia às 3h ({@code app.seguranca.refresh.limpeza.cron}). */
  @Scheduled(cron = "${app.seguranca.refresh.limpeza.cron:0 0 3 * * *}", zone = "America/Sao_Paulo")
  @SchedulerLock(
      name = LOCK,
      lockAtMostFor = "${app.seguranca.refresh.limpeza.lock.at-most-for:PT30M}",
      lockAtLeastFor = "${app.seguranca.refresh.limpeza.lock.at-least-for:PT5M}")
  @Transactional
  public void limpar() {
    OffsetDateTime corte = OffsetDateTime.now(clock).minus(propriedades.retencao());
    int apagadas = sessaoRepository.apagarExpiradasAntes(corte);
    if (apagadas > 0) {
      log.info("Limpeza de sessoes de refresh: {} apagada(s), vencidas antes de {}", apagadas,
          corte);
    }
  }
}
