package br.com.agrocontabil.config;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import net.javacrumbs.shedlock.support.KeepAliveLockProvider;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Habilita o agendamento de tarefas ({@code @Scheduled}) e o lock distribuído dos jobs.
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
 * {@code await-termination} no encerramento. Isso coordena apenas as threads <em>desta</em>
 * instância.
 *
 * <p><strong>Lock distribuído (issue #87):</strong> com 2+ instâncias, cada réplica dispararia
 * todos os jobs e a sincronização SEFAZ, o fechamento da apuração e a marcação de guias rodariam
 * em dobro. Cada job tem {@code @SchedulerLock} com nome próprio; o ShedLock grava o lock na
 * tabela {@code shedlock} (changeSet {@code 0023}) e a réplica que não conseguir o lock
 * simplesmente pula aquela execução.
 *
 * <ul>
 *   <li><strong>{@code usingDbTime()}:</strong> {@code lock_until} é calculado com o relógio do
 *       banco, não o de cada instância — um relógio adiantado numa réplica não expira o lock da
 *       outra antes da hora.</li>
 *   <li><strong>Keep-alive:</strong> a duração de um job não é conhecida de antemão (a
 *       sincronização SEFAZ faz uma chamada de rede com retry por nota pendente). Um
 *       {@code lockAtMostFor} fixo expiraria no meio de uma execução longa, e outra réplica
 *       começaria a mesma varredura em paralelo. O {@link KeepAliveLockProvider} renova o lock a
 *       cada metade do {@code lockAtMostFor} enquanto o job roda; o teto passa a valer só para
 *       liberar o lock de uma réplica que morreu.</li>
 *   <li><strong>{@code withThrowUnexpectedException(true)}:</strong> erro inesperado no
 *       armazenamento do lock (tabela ausente, permissão) sobe ao scheduler em vez de virar
 *       "lock não adquirido" — senão o job seria pulado em silêncio a cada disparo.</li>
 *   <li><strong>{@code order = HIGHEST_PRECEDENCE}:</strong> o default do ShedLock é
 *       {@code LOWEST_PRECEDENCE}, a mesma ordem do advice transacional; com empate a ordem entre
 *       os dois fica indefinida. Precedência máxima põe o lock <em>por fora</em> do
 *       {@code @Transactional} do job: o lock só é liberado depois do commit, e outra réplica não
 *       começa a mesma varredura enquanto a anterior ainda não gravou.</li>
 *   <li><strong>{@code defaultLockAtMostFor}:</strong> rede de segurança para um job futuro que
 *       esqueça de declarar o seu; os três jobs atuais declaram durações próprias.</li>
 * </ul>
 */
@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT30M", order = Ordered.HIGHEST_PRECEDENCE)
public class SchedulingConfig implements DisposableBean {

  /** Tabela do lock, criada pelo changeSet {@code 0023-shedlock.xml}. */
  public static final String TABELA_LOCK = "shedlock";

  /**
   * Thread que renova os locks dos jobs em andamento.
   *
   * <p><strong>Não é um bean de propósito.</strong> Um {@link ScheduledExecutorService} no
   * contexto seria escolhido pelo {@code @EnableScheduling} como scheduler dos jobs e desligaria
   * o {@code ThreadPoolTaskScheduler} autoconfigurado pelo Boot (pool da issue #101), que é
   * condicional à ausência de um. Por isso vive aqui e é encerrado em {@link #destroy()}.
   */
  private final ScheduledExecutorService keepAliveExecutor =
      Executors.newSingleThreadScheduledExecutor(tarefa -> {
        Thread thread = new Thread(tarefa, "shedlock-keepalive");
        thread.setDaemon(true);
        return thread;
      });

  /**
   * Provedor de lock dos jobs: JDBC na tabela {@value #TABELA_LOCK}, com o relógio do banco, e
   * renovação automática enquanto o job roda.
   *
   * @param jdbcTemplate {@code JdbcTemplate} autoconfigurado (mesmo banco para todas as réplicas)
   * @return provedor de lock usado pelos {@code @SchedulerLock}
   */
  @Bean
  public LockProvider lockProvider(JdbcTemplate jdbcTemplate) {
    return new KeepAliveLockProvider(jdbcLockProvider(jdbcTemplate), keepAliveExecutor);
  }

  /**
   * Provedor JDBC com a configuração do projeto, sem o keep-alive. Exposto para os testes que
   * exercitam o SQL do lock (aquisição e extensão) diretamente contra H2 e PostgreSQL.
   *
   * @param jdbcTemplate acesso ao banco onde fica a tabela {@value #TABELA_LOCK}
   * @return provedor JDBC com relógio do banco e erro inesperado propagado
   */
  public static JdbcTemplateLockProvider jdbcLockProvider(JdbcTemplate jdbcTemplate) {
    return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
        .withJdbcTemplate(jdbcTemplate)
        .withTableName(TABELA_LOCK)
        .usingDbTime()
        .withThrowUnexpectedException(true)
        .build());
  }

  /** Encerra a thread de keep-alive junto com o contexto. */
  @Override
  public void destroy() {
    keepAliveExecutor.shutdownNow();
  }
}
