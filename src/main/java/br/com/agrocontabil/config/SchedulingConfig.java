package br.com.agrocontabil.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Habilita o agendamento de tarefas ({@code @Scheduled}) no contexto Spring.
 *
 * <p>Jobs agendados hoje, todos condicionados por propriedade (desligados no perfil de testes):
 * <ul>
 *   <li>{@link br.com.agrocontabil.sefaz.SefazStatusSyncJob} — sincroniza notas pendentes na
 *       SEFAZ (issue #16)</li>
 *   <li>{@link br.com.agrocontabil.service.ApuracaoFechamentoJob} — fechamento mensal da
 *       apuração e refechamento de períodos reabertos (issues #41/#57)</li>
 *   <li>{@link br.com.agrocontabil.service.GuiaFiscalStatusSyncJob} — marca guias vencidas
 *       (issue #74)</li>
 * </ul>
 *
 * <p><strong>Pool de execução (issue #101):</strong> não há bean de {@code TaskScheduler} aqui.
 * Na ausência de um, o Spring Boot autoconfigura um {@code ThreadPoolTaskScheduler} a partir de
 * {@code spring.task.scheduling.*} no {@code application.yml} — pool de 3 threads (uma por job,
 * ajustável por {@code SCHEDULER_POOL_SIZE}), prefixo {@code agro-sched-} e
 * {@code await-termination} no encerramento. Antes disso o pool default tinha uma única thread
 * e os três jobs se serializavam: um job lento atrasava os demais.
 *
 * <p>Isso coordena apenas as threads <em>desta</em> instância. Impedir que duas instâncias
 * rodem o mesmo job simultaneamente exige lock distribuído — issue #87.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {}
