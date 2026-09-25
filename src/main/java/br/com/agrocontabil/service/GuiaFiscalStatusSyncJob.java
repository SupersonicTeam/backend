package br.com.agrocontabil.service;

import br.com.agrocontabil.model.GuiaFiscal;
import br.com.agrocontabil.model.StatusGuiaFiscal;
import br.com.agrocontabil.repository.GuiaFiscalRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Job agendado que transiciona guias fiscais {@link StatusGuiaFiscal#PENDENTE} vencidas para
 * {@link StatusGuiaFiscal#VENCIDA} (issue #74), mesmo padrão do
 * {@code SefazStatusSyncJob}/{@code ApuracaoFechamentoJob}.
 *
 * <p>Ativo somente quando {@code app.guia-fiscal.sync.enabled=true} (padrão). Desabilitado no
 * perfil de testes ({@code application-test.yml}) para não interferir nas asserções.
 */
@Component
@ConditionalOnProperty(name = "app.guia-fiscal.sync.enabled", havingValue = "true",
    matchIfMissing = true)
public class GuiaFiscalStatusSyncJob {

  private static final Logger log = LoggerFactory.getLogger(GuiaFiscalStatusSyncJob.class);

  /**
   * Nome do lock distribuído deste job (issue #87): a marcação diária roda numa réplica só.
   *
   * <p>Teto de 30 min para um {@code UPDATE} em lote curto; piso de 5 min pelo mesmo motivo do
   * fechamento — desvio de relógio entre réplicas que disparam o mesmo cron. O lock envolve o
   * {@code @Transactional} deste método (ver {@code SchedulingConfig}): só é liberado depois do
   * commit.
   */
  public static final String LOCK = "guia-fiscal-status-sync";

  private final GuiaFiscalRepository guiaFiscalRepository;
  private final Clock clock;

  public GuiaFiscalStatusSyncJob(GuiaFiscalRepository guiaFiscalRepository, Clock clock) {
    this.guiaFiscalRepository = guiaFiscalRepository;
    this.clock = clock;
  }

  /**
   * Marca como VENCIDA toda guia PENDENTE cuja {@code dataVencimento} é anterior a hoje
   * ({@code America/Sao_Paulo}).
   *
   * <p>Roda uma vez por dia, à 1h ({@code app.guia-fiscal.sync.cron}, padrão
   * {@code "0 0 1 * * *"}).
   */
  @Scheduled(cron = "${app.guia-fiscal.sync.cron:0 0 1 * * *}", zone = "America/Sao_Paulo")
  @SchedulerLock(
      name = LOCK,
      lockAtMostFor = "${app.guia-fiscal.sync.lock.at-most-for:PT30M}",
      lockAtLeastFor = "${app.guia-fiscal.sync.lock.at-least-for:PT5M}")
  @Transactional(timeoutString = "${app.guia-fiscal.sync.transaction-timeout:600}")
  public void sincronizarVencidas() {
    LocalDate hoje = LocalDate.now(clock);
    List<GuiaFiscal> vencidas =
        guiaFiscalRepository.findByStatusAndDataVencimentoBefore(StatusGuiaFiscal.PENDENTE, hoje);
    if (vencidas.isEmpty()) {
      return;
    }
    vencidas.forEach(guia -> guia.setStatus(StatusGuiaFiscal.VENCIDA));
    guiaFiscalRepository.saveAll(vencidas);
    log.info("Sincronizacao de guias fiscais concluida: {} guia(s) marcada(s) como VENCIDA",
        vencidas.size());
  }
}
