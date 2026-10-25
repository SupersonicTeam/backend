package br.com.agrocontabil.service;

import br.com.agrocontabil.repository.PendenciaAnexoRepository;
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
 * Apaga os anexos das pendências enviados há mais de {@code app.pendencias.anexo.retencao-anos}
 * (issue #180). O anexo é cópia de documento que o escritório usou para escriturar: guarda pelos
 * mesmos 5 anos dos documentos fiscais (CTN art. 173) e depois sai (LGPD art. 15) — o arquivo
 * pode conter dado pessoal de terceiros. A pendência fica, só sem o arquivo.
 */
@Component
@ConditionalOnProperty(name = "app.pendencias.anexo.retencao.enabled", havingValue = "true",
    matchIfMissing = true)
public class PendenciaAnexoRetencaoJob {

  private static final Logger log = LoggerFactory.getLogger(PendenciaAnexoRetencaoJob.class);

  /** Nome do lock distribuído: a retenção roda numa réplica só. */
  public static final String LOCK = "pendencia-anexo-retencao";

  /** Abaixo disso o anexo sairia antes do prazo de guarda dos documentos fiscais. */
  static final int RETENCAO_MINIMA_ANOS = 5;

  private final PendenciaAnexoRepository anexoRepository;
  private final int retencaoAnos;
  private final Clock clock;

  public PendenciaAnexoRetencaoJob(PendenciaAnexoRepository anexoRepository,
      @Value("${app.pendencias.anexo.retencao-anos:5}") int retencaoAnos, Clock clock) {
    if (retencaoAnos < RETENCAO_MINIMA_ANOS) {
      throw new IllegalStateException("app.pendencias.anexo.retencao-anos deve ser ao menos "
          + RETENCAO_MINIMA_ANOS + " (guarda dos documentos fiscais)");
    }
    this.anexoRepository = anexoRepository;
    this.retencaoAnos = retencaoAnos;
    this.clock = clock;
  }

  /** Todo dia às 3h45 ({@code app.pendencias.anexo.retencao.cron}). */
  @Scheduled(cron = "${app.pendencias.anexo.retencao.cron:0 45 3 * * *}",
      zone = "America/Sao_Paulo")
  @SchedulerLock(
      name = LOCK,
      lockAtMostFor = "${app.pendencias.anexo.retencao.lock.at-most-for:PT30M}",
      lockAtLeastFor = "${app.pendencias.anexo.retencao.lock.at-least-for:PT5M}")
  @Transactional
  public void aplicar() {
    OffsetDateTime corte = OffsetDateTime.now(clock).minusYears(retencaoAnos);
    int apagados = anexoRepository.apagarAnterioresA(corte);
    if (apagados > 0) {
      log.info("Retencao de anexos das pendencias: {} apagado(s), enviados antes de {}", apagados,
          corte);
    }
  }
}
