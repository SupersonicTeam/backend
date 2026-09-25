package br.com.agrocontabil.service;

import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Job agendado de fechamento automático da apuração mensal de IBS/CBS
 * (issue Agrocontabil#41 — RN004).
 *
 * <p>No último dia de cada mês, às 23h ({@code cron = "0 0 23 L * *"}, fuso
 * {@code America/Sao_Paulo}), consolida e marca como {@link br.com.agrocontabil.model.StatusApuracao#FECHADO}
 * a apuração do mês corrente de todos os produtores.
 *
 * <p>Ativo somente quando {@code app.apuracao.fechamento.enabled=true} (padrão). Desabilitado no
 * perfil de testes ({@code application-test.yml}) para não interferir nas asserções — mesmo padrão
 * do {@code SefazStatusSyncJob}.
 *
 * <p>Desde a issue #100 cada produtor é fechado em transação própria: a falha de um não desfaz
 * o fechamento dos demais. O job loga o resumo e sobe para {@code ERROR} com os IDs que falharam.
 */
@Component
@ConditionalOnProperty(name = "app.apuracao.fechamento.enabled", havingValue = "true",
    matchIfMissing = true)
public class ApuracaoFechamentoJob {

  private static final Logger log = LoggerFactory.getLogger(ApuracaoFechamentoJob.class);

  /** Fuso de referência do projeto para determinar o mês corrente. */
  private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

  /**
   * Nome do lock distribuído deste job (issue #87): o fechamento mensal roda numa réplica só.
   *
   * <p>Teto de 50 min: cobre a varredura em lotes com folga e libera o lock se o nó morrer no
   * meio. Piso de 5 min: as réplicas disparam o mesmo cron ao mesmo tempo, e o piso cobre o
   * desvio de relógio entre elas — uma réplica atrasada não refaz o fechamento que a outra acabou
   * de concluir.
   */
  public static final String LOCK = "apuracao-fechamento";

  /** Quantos IDs com falha o resumo do job lista no log (issue #100). */
  private static final int MAX_IDS_NO_LOG = 20;

  private final ApuracaoService apuracaoService;

  /** Constrói o job com o serviço de apuração. */
  public ApuracaoFechamentoJob(ApuracaoService apuracaoService) {
    this.apuracaoService = apuracaoService;
  }

  /**
   * Fecha a apuração do mês corrente de todos os produtores e refecha os períodos reabertos.
   *
   * <p>Disparado às 23h do último dia do mês ({@code L} do cron). O horário do cron respeita o fuso
   * configurado ({@code America/Sao_Paulo}). Após fechar o mês corrente, varre e refecha as
   * apurações reabertas de meses anteriores ainda dentro da janela da RN005 (issue
   * Agrocontabil#57), que de outra forma permaneceriam ABERTAS indefinidamente.
   */
  @Scheduled(cron = "0 0 23 L * *", zone = "America/Sao_Paulo")
  @SchedulerLock(
      name = LOCK,
      lockAtMostFor = "${app.apuracao.fechamento.lock.at-most-for:PT50M}",
      lockAtLeastFor = "${app.apuracao.fechamento.lock.at-least-for:PT5M}")
  public void fecharApuracaoDoMes() {
    String periodo = YearMonth.now(ZONE).toString();
    log.info("Iniciando fechamento automatico de apuracao: periodo={}", periodo);
    ResumoLoteApuracao fechamento = apuracaoService.fecharPeriodo(periodo);
    ResumoLoteApuracao refechamento = apuracaoService.refecharPeriodosReabertos();
    log.info("Fechamento automatico concluido: periodo={} apuracoes={} reabertas_refechadas={}",
        periodo, fechamento.concluidas(), refechamento.concluidas());
    // issue #100: cada produtor fecha em transacao propria; uma falha nao aborta o lote, mas
    // precisa aparecer no log de forma agregada para alguem reprocessar o produtor afetado.
    if (fechamento.houveFalha() || refechamento.houveFalha()) {
      log.error("Fechamento automatico com falhas: periodo={} produtores_com_falha={} {} "
              + "apuracoes_reabertas_com_falha={} {}",
          periodo, fechamento.falhas().size(), amostra(fechamento),
          refechamento.falhas().size(), amostra(refechamento));
    }
  }

  /**
   * Primeiros IDs com falha, para o log. Com o banco fora do ar todos os produtores falham, e
   * listar centenas de UUIDs numa linha só polui o log; o detalhe de cada um já saiu no log do
   * {@link ApuracaoService}.
   */
  private static List<UUID> amostra(ResumoLoteApuracao resumo) {
    List<UUID> falhas = resumo.falhas();
    return falhas.subList(0, Math.min(falhas.size(), MAX_IDS_NO_LOG));
  }
}
