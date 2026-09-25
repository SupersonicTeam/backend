package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.config.SchedulingConfig;
import br.com.agrocontabil.model.GuiaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusGuiaFiscal;
import br.com.agrocontabil.model.TipoGuiaFiscal;
import br.com.agrocontabil.repository.GuiaFiscalRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.support.TestDatabaseCleaner;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import net.javacrumbs.shedlock.support.KeepAliveLockProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.Advised;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;

/**
 * Teste de integração do lock distribuído dos jobs (issue #87), com o ShedLock real sobre o H2.
 *
 * <p>Exercita o {@link GuiaFiscalStatusSyncJob}, o único dos três jobs cujo efeito é verificável
 * sem SEFAZ nem fechamento mensal. O job é ligado com o cron desativado ({@code -}): o bean existe
 * com o proxy do ShedLock, mas o scheduler nunca o dispara sozinho. O teste chama o método pelo
 * proxy, que é exatamente o que o scheduler de cada réplica faz.
 *
 * <p>"Outra instância" é simulada adquirindo o lock direto no {@link LockProvider}, na mesma tabela
 * {@code shedlock} que uma segunda réplica usaria.
 */
@SpringBootTest(properties = {
    "app.guia-fiscal.sync.enabled=true",
    "app.guia-fiscal.sync.cron=-"
})
@Import(TestDatabaseCleaner.class)
@ActiveProfiles("test")
@Sql(scripts = {"classpath:schema-nota-fiscal-test.sql", "classpath:schema-shedlock-test.sql"},
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
class JobLockDistribuidoIntegrationTest {

  @Autowired private GuiaFiscalStatusSyncJob job;
  @Autowired private LockProvider lockProvider;
  @Autowired private GuiaFiscalRepository guiaFiscalRepository;
  @Autowired private ProdutorRepository produtorRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private TestDatabaseCleaner cleaner;

  private Produtor produtor;

  @BeforeEach
  void setUp() {
    cleaner.limparTudo();
    // Expira os locks em vez de apaga-los: o provider guarda em memoria os nomes que ja inseriu e,
    // para esses, so tenta UPDATE ... WHERE lock_until <= agora. Linha apagada = lock impossivel.
    jdbcTemplate.update("UPDATE shedlock SET lock_until = TIMESTAMP '2000-01-01 00:00:00'");
    produtor = produtorRepository.save(
        Produtor.builder().nomeCompleto("Maria Agricultora").cpfCnpj("98765432100").build());
  }

  @Test
  @DisplayName("Com o lock em poder de outra instancia, o job nao executa")
  void lockDeOutraInstanciaImpedeExecucao() {
    GuiaFiscal vencida = guiaVencida();
    SimpleLock daOutraInstancia = adquirirComoOutraInstancia();

    try {
      job.sincronizarVencidas();

      assertThat(statusDe(vencida)).isEqualTo(StatusGuiaFiscal.PENDENTE);
    } finally {
      daOutraInstancia.unlock();
    }

    // Liberado o lock (a outra instancia terminou), a execucao seguinte roda normalmente.
    job.sincronizarVencidas();

    assertThat(statusDe(vencida)).isEqualTo(StatusGuiaFiscal.VENCIDA);
  }

  @Test
  @DisplayName("Duas replicas disparando o mesmo cron: so a primeira executa (lockAtLeastFor)")
  void mesmoCronEmDuasReplicasExecutaUmaVez() {
    GuiaFiscal primeira = guiaVencida();

    job.sincronizarVencidas(); // replica A
    assertThat(statusDe(primeira)).isEqualTo(StatusGuiaFiscal.VENCIDA);

    // Uma guia vencida aparece e a replica B dispara o mesmo cron logo depois (desvio de relogio).
    // O piso de 5 min mantem o lock de A: B pula a execucao em vez de repetir a varredura.
    GuiaFiscal segunda = guiaVencida();
    job.sincronizarVencidas(); // replica B

    assertThat(statusDe(segunda)).isEqualTo(StatusGuiaFiscal.PENDENTE);
    Integer linhas = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM shedlock WHERE name = ?", Integer.class,
        GuiaFiscalStatusSyncJob.LOCK);
    assertThat(linhas).isEqualTo(1);
  }

  /**
   * Confere a ordem real dos advisors no proxy hoje. Não prova, sozinho, a necessidade do
   * {@code order = HIGHEST_PRECEDENCE}: sem ele os dois empatam e a ordem de registro calha de
   * dar o mesmo resultado (falsificado na issue #87). Quem trava a configuração é o
   * {@code SchedulerLockConfigTest}.
   */
  @Test
  @DisplayName("No proxy do job, o advisor do lock vem antes do transacional")
  void lockEnvolveATransacaoDoJob() {
    assertThat(job).isInstanceOf(Advised.class);
    List<String> advisors = Arrays.stream(((Advised) job).getAdvisors())
        .map(Advisor::getClass)
        .map(Class::getName)
        .toList();

    int posicaoLock = indiceDe(advisors, "net.javacrumbs.shedlock");
    int posicaoTransacao = indiceDe(advisors, "org.springframework.transaction");

    assertThat(posicaoLock)
        .as("advisors do proxy: %s", advisors)
        .isLessThan(posicaoTransacao);
  }

  @Test
  @DisplayName("Provider renova o lock enquanto o job roda (keep-alive) e estende no H2")
  void providerRenovaOLockEnquantoOJobRoda() {
    assertThat(lockProvider).isInstanceOf(KeepAliveLockProvider.class);

    // O keep-alive estende o lock pelo SQL do provider JDBC; confere esse SQL no dialeto do H2.
    LockConfiguration config = new LockConfiguration(
        Instant.now(), "teste-extensao", Duration.ofMinutes(1), Duration.ZERO);
    SimpleLock lock = SchedulingConfig.jdbcLockProvider(jdbcTemplate).lock(config).orElseThrow();
    Instant antes = lockUntil("teste-extensao");

    Optional<SimpleLock> estendido = lock.extend(Duration.ofMinutes(10), Duration.ZERO);

    assertThat(estendido).isPresent();
    assertThat(lockUntil("teste-extensao")).isAfter(antes);
    estendido.get().unlock();
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private SimpleLock adquirirComoOutraInstancia() {
    Optional<SimpleLock> lock = lockProvider.lock(new LockConfiguration(
        Instant.now(), GuiaFiscalStatusSyncJob.LOCK, Duration.ofMinutes(5), Duration.ZERO));
    assertThat(lock).as("a outra instancia deveria conseguir o lock livre").isPresent();
    return lock.get();
  }

  private GuiaFiscal guiaVencida() {
    return guiaFiscalRepository.save(GuiaFiscal.builder()
        .produtor(produtor)
        .tipoGuia(TipoGuiaFiscal.FUNRURAL)
        .dataVencimento(LocalDate.now().minusDays(10))
        .valor(new BigDecimal("300.00"))
        .status(StatusGuiaFiscal.PENDENTE)
        .build());
  }

  private StatusGuiaFiscal statusDe(GuiaFiscal guia) {
    return guiaFiscalRepository.findById(guia.getId()).orElseThrow().getStatus();
  }

  private Instant lockUntil(String nome) {
    return jdbcTemplate.queryForObject(
        "SELECT lock_until FROM shedlock WHERE name = ?", java.sql.Timestamp.class, nome)
        .toInstant();
  }

  private static int indiceDe(List<String> nomes, String prefixo) {
    for (int i = 0; i < nomes.size(); i++) {
      if (nomes.get(i).startsWith(prefixo)) {
        return i;
      }
    }
    throw new AssertionError("nenhum advisor " + prefixo + " no proxy: " + nomes);
  }
}
