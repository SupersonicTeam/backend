package br.com.agrocontabil.service;

import java.time.YearMonth;
import java.time.ZoneId;
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
 */
@Component
@ConditionalOnProperty(name = "app.apuracao.fechamento.enabled", havingValue = "true",
    matchIfMissing = true)
public class ApuracaoFechamentoJob {

  private static final Logger log = LoggerFactory.getLogger(ApuracaoFechamentoJob.class);

  /** Fuso de referência do projeto para determinar o mês corrente. */
  private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

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
  public void fecharApuracaoDoMes() {
    String periodo = YearMonth.now(ZONE).toString();
    log.info("Iniciando fechamento automatico de apuracao: periodo={}", periodo);
    int fechadas = apuracaoService.fecharPeriodo(periodo);
    int refechadas = apuracaoService.refecharPeriodosReabertos();
    log.info("Fechamento automatico concluido: periodo={} apuracoes={} reabertas_refechadas={}",
        periodo, fechadas, refechadas);
  }
}
