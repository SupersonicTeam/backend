package br.com.agrocontabil.service;

import br.com.agrocontabil.service.VerificacaoAnexoService.ResumoVerificacao;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Reverifica os anexos que ficaram {@code EM_VERIFICACAO} (issue #227): o antivírus estava fora
 * do ar no envio, ou o anexo é anterior à #227. Roda a cada
 * {@code app.pendencias.antivirus.reverificacao.intervalo-ms} (2 min), numa réplica só
 * (ShedLock), e não é {@code @Transactional}: cada anexo tem as transações curtas dele, com a
 * chamada ao antivírus fora delas.
 */
@Component
@ConditionalOnProperty(name = "app.pendencias.antivirus.reverificacao.enabled",
    havingValue = "true", matchIfMissing = true)
public class VerificacaoAnexoJob {

  private static final Logger log = LoggerFactory.getLogger(VerificacaoAnexoJob.class);

  /** Nome do lock distribuído: duas réplicas não verificam o mesmo anexo. */
  public static final String LOCK = "pendencia-anexo-verificacao";

  private final VerificacaoAnexoService verificacao;

  public VerificacaoAnexoJob(VerificacaoAnexoService verificacao) {
    this.verificacao = verificacao;
  }

  /** Um lote por execução ({@code app.pendencias.antivirus.lote-tamanho}). */
  @Scheduled(
      fixedDelayString = "${app.pendencias.antivirus.reverificacao.intervalo-ms:120000}",
      initialDelayString = "${app.pendencias.antivirus.reverificacao.intervalo-ms:120000}")
  @SchedulerLock(
      name = LOCK,
      lockAtMostFor = "${app.pendencias.antivirus.reverificacao.lock.at-most-for:PT15M}",
      lockAtLeastFor = "${app.pendencias.antivirus.reverificacao.lock.at-least-for:PT1M}")
  public void reverificar() {
    ResumoVerificacao resumo = verificacao.reprocessarPendentes();
    if (resumo.limpos() + resumo.infectados() + resumo.semVeredito() > 0) {
      log.info("Reverificacao de anexos: {} limpo(s), {} infectado(s), {} sem veredito{}",
          resumo.limpos(), resumo.infectados(), resumo.semVeredito(),
          resumo.antivirusForaDoAr() ? " (antivirus fora do ar)" : "");
    }
  }
}
