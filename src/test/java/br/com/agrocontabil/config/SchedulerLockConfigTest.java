package br.com.agrocontabil.config;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.sefaz.SefazStatusSyncJob;
import br.com.agrocontabil.service.AlertaTributarioJob;
import br.com.agrocontabil.service.ApuracaoFechamentoJob;
import br.com.agrocontabil.service.GuiaFiscalStatusSyncJob;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Trava a configuração do lock distribuído dos jobs (issue #87) sem subir contexto.
 *
 * <p>Um {@code @Scheduled} novo sem {@code @SchedulerLock} voltaria a rodar em dobro com 2+
 * instâncias, e nada quebraria em teste de uma instância só. Este teste varre os jobs conhecidos e
 * exige lock em todo método agendado, com nome único e teto explícito.
 */
class SchedulerLockConfigTest {

  private static final List<Class<?>> JOBS = List.of(
      SefazStatusSyncJob.class, ApuracaoFechamentoJob.class, GuiaFiscalStatusSyncJob.class,
      AlertaTributarioJob.class);

  @Test
  @DisplayName("Todo metodo @Scheduled dos jobs tem @SchedulerLock com teto e piso declarados")
  void todoMetodoAgendadoTemLock() {
    List<Method> agendados = metodosAgendados();

    assertThat(agendados).hasSize(JOBS.size());
    assertThat(agendados).allSatisfy(metodo -> {
      SchedulerLock lock = metodo.getAnnotation(SchedulerLock.class);
      assertThat(lock)
          .as("%s#%s rodaria em dobro com 2+ instancias sem @SchedulerLock",
              metodo.getDeclaringClass().getSimpleName(), metodo.getName())
          .isNotNull();
      assertThat(lock.lockAtMostFor()).isNotBlank();
      assertThat(lock.lockAtLeastFor()).isNotBlank();
    });
  }

  @Test
  @DisplayName("Cada job usa um nome de lock proprio, igual a sua constante LOCK")
  void nomesDeLockSaoUnicos() {
    List<String> nomes = metodosAgendados().stream()
        .map(m -> m.getAnnotation(SchedulerLock.class).name())
        .toList();

    // Dois jobs com o mesmo nome se bloqueariam mutuamente: um deixaria de rodar.
    assertThat(nomes).doesNotHaveDuplicates()
        .containsExactlyInAnyOrder(SefazStatusSyncJob.LOCK, ApuracaoFechamentoJob.LOCK,
            GuiaFiscalStatusSyncJob.LOCK, AlertaTributarioJob.LOCK);
  }

  @Test
  @DisplayName("Lock tem precedencia maxima para envolver o @Transactional do job")
  void lockEnvolveATransacao() {
    EnableSchedulerLock config = SchedulingConfig.class.getAnnotation(EnableSchedulerLock.class);

    assertThat(config).isNotNull();
    // O default do ShedLock (LOWEST_PRECEDENCE) empata com o advice transacional e deixa a ordem
    // indefinida; com o lock por dentro, ele seria liberado antes do commit.
    assertThat(config.order()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    assertThat(config.defaultLockAtMostFor()).isNotBlank();
  }

  private static List<Method> metodosAgendados() {
    return JOBS.stream()
        .flatMap(job -> Arrays.stream(job.getDeclaredMethods()))
        .filter(m -> m.isAnnotationPresent(Scheduled.class))
        .toList();
  }
}
