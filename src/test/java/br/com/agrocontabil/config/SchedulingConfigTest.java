package br.com.agrocontabil.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.Shutdown;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.ActiveProfiles;

/**
 * Trava a configuração do agendamento e do encerramento da aplicação (issue #101).
 *
 * <p>Com apenas {@code @EnableScheduling} e nenhuma propriedade, o Spring Boot cria um scheduler
 * de <strong>uma</strong> thread: os três jobs do sistema ({@code SefazStatusSyncJob},
 * {@code ApuracaoFechamentoJob} e {@code GuiaFiscalStatusSyncJob}) disputavam essa thread, de
 * modo que um job lento atrasava os demais. E, sem {@code server.shutdown=graceful}, um deploy
 * derrubava o processo no meio de uma requisição ou de um fechamento de apuração.
 *
 * <p>Os jobs em si continuam desligados no profile de teste (por propriedade); o que se verifica
 * aqui é a infraestrutura que os executa.
 */
@SpringBootTest
@ActiveProfiles("test")
class SchedulingConfigTest {

  @Autowired
  private ThreadPoolTaskScheduler taskScheduler;

  @Autowired
  private ServerProperties serverProperties;

  @Autowired
  private Environment environment;

  @Test
  @DisplayName("scheduler deve ter pool dedicado de 3 threads com prefixo proprio")
  void schedulerDeveTerPoolDedicado() {
    // Uma thread por job: nenhum job espera o outro terminar.
    // getPoolSize() conta as threads JA criadas (o pool as cria sob demanda, e no profile de
    // teste os jobs estao desligados); o tamanho configurado esta no core pool size.
    assertThat(taskScheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(3);
    // Prefixo proprio torna o job identificavel no log e num thread dump.
    assertThat(taskScheduler.getThreadNamePrefix()).isEqualTo("agro-sched-");
  }

  @Test
  @DisplayName("scheduler deve esperar os jobs em andamento no shutdown")
  void schedulerDeveEsperarJobsNoShutdown() {
    assertThat(environment.getProperty("spring.task.scheduling.shutdown.await-termination"))
        .as("sem await-termination, um fechamento de apuracao em andamento morreria no deploy")
        .isEqualTo("true");
    assertThat(
        environment.getProperty("spring.task.scheduling.shutdown.await-termination-period"))
        .isEqualTo("30s");
  }

  @Test
  @DisplayName("aplicacao deve encerrar de forma graceful com janela de 30s")
  void aplicacaoDeveEncerrarGraceful() {
    assertThat(serverProperties.getShutdown()).isEqualTo(Shutdown.GRACEFUL);
    assertThat(environment.getProperty("spring.lifecycle.timeout-per-shutdown-phase"))
        .isEqualTo("30s");
  }
}
