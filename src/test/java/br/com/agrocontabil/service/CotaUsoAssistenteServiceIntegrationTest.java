package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.agrocontabil.config.AssistenteProperties;
import br.com.agrocontabil.exception.LimiteUsoAssistenteException;
import br.com.agrocontabil.model.TipoTitularCota;
import br.com.agrocontabil.model.UsoSemanalAssistente;
import br.com.agrocontabil.model.UsoSemanalAssistenteId;
import br.com.agrocontabil.repository.UsoSemanalAssistenteRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Cota semanal da Ceres contra o banco (issue #166): créditos, teto, acerto, estorno, virada da
 * semana em {@code America/Sao_Paulo} e reserva concorrente sem ultrapassar o limite. Sem
 * {@code @Transactional} de classe: cada passo do service commita a própria transação, e é isso que
 * o teste de concorrência precisa provar.
 */
@SpringBootTest
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
class CotaUsoAssistenteServiceIntegrationTest {

  private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
  /** Quarta, 14/10/2026: a semana começou na segunda, 12/10. */
  private static final LocalDateTime QUARTA = LocalDateTime.of(2026, 10, 14, 10, 0);

  @Autowired private UsoSemanalAssistenteRepository repository;
  @Autowired private PlatformTransactionManager transactionManager;

  private AssistenteProperties propriedades;
  private UUID produtor;

  @BeforeEach
  void setUp() {
    repository.deleteAll();
    propriedades = new AssistenteProperties();
    propriedades.getCota().setHabilitada(true);
    propriedades.getCota().setCreditosPorSemanaProdutor(300);
    produtor = UUID.randomUUID();
  }

  @Test
  @DisplayName("Creditos: saida pesa mais, fator do modelo multiplica, arredonda para cima")
  void calculoDeCreditos() {
    propriedades.getCota().getFatorPorModelo().put("openai/gpt-4o", 3.0);
    CotaUsoAssistenteService service = service(QUARTA);

    // Pergunta simples: 1.000 de entrada + 100 de saida = 1.400 ponderados = 2 creditos.
    assertThat(service.creditos(1000, 100, "ollama/qwen3")).isEqualTo(2);
    // Pergunta complexa (rodadas de funcao, trechos de lei): 12.000 + 800 = 15.200 -> 16.
    assertThat(service.creditos(12_000, 800, "ollama/qwen3")).isEqualTo(16);
    // Modelo maior: mesmo consumo, fator 3.
    assertThat(service.creditos(1000, 100, "openai/gpt-4o")).isEqualTo(5);
    // Fallback: sem modelo, sem custo.
    assertThat(service.creditos(0, 0, "ollama/qwen3")).isZero();
    // Teto: (2 + 1 + 3 rodadas) chamadas x (8.000 + 1.024 x 4) / 1.000 = 72,6 -> 73.
    assertThat(service.tetoDaPergunta("ollama/qwen3")).isEqualTo(73);
  }

  @Test
  @DisplayName("Acerto debita o real e devolve a diferenca; estorno devolve tudo")
  void acertoEEstorno() {
    CotaUsoAssistenteService service = service(QUARTA);

    CotaUsoAssistenteService.Reserva primeira =
        service.reservar(TipoTitularCota.PRODUTOR, produtor, "ollama/qwen3");
    assertThat(uso().getCreditosReservados()).isEqualTo(73);
    assertThat(service.acertar(primeira, 1000, 100, "ollama/qwen3")).isEqualTo(2);
    assertThat(uso().getCreditosReservados()).isZero();
    assertThat(uso().getCreditosConsumidos()).isEqualTo(2);
    assertThat(uso().getTokensEntrada()).isEqualTo(1000);
    assertThat(uso().getPerguntas()).isEqualTo(1);

    CotaUsoAssistenteService.Reserva segunda =
        service.reservar(TipoTitularCota.PRODUTOR, produtor, "ollama/qwen3");
    service.estornar(segunda);
    // Estorno repetido nao vira credito de graca: a reserva ja nao esta la.
    service.estornar(segunda);
    assertThat(uso().getCreditosReservados()).isZero();
    assertThat(uso().getCreditosConsumidos()).isEqualTo(2);

    CotaUsoAssistenteService.Uso consulta = service.consultar(TipoTitularCota.PRODUTOR, produtor);
    assertThat(consulta.consumidos()).isEqualTo(2);
    assertThat(consulta.restantes()).isEqualTo(298);
    assertThat(consulta.renovaEm().toLocalDate()).isEqualTo(LocalDate.of(2026, 10, 19));
  }

  @Test
  @DisplayName("Sem saldo para o teto: 429 com Retry-After ate a segunda 00:00 de Brasilia")
  void semSaldo() {
    repository.save(UsoSemanalAssistente.builder()
        .id(new UsoSemanalAssistenteId(TipoTitularCota.PRODUTOR, produtor,
            LocalDate.of(2026, 10, 12)))
        .creditosConsumidos(250)
        .atualizadoEm(QUARTA.atZone(ZONE).toOffsetDateTime())
        .build());
    CotaUsoAssistenteService service = service(QUARTA);

    // 250 consumidos + 73 de teto > 300.
    assertThatThrownBy(() -> service.reservar(TipoTitularCota.PRODUTOR, produtor, "ollama/qwen3"))
        .isInstanceOfSatisfying(LimiteUsoAssistenteException.class, e ->
            // Quarta 10h ate segunda 00h: 4 dias e 14 horas.
            assertThat(e.retryAfterSegundos()).isEqualTo((4 * 24 + 14) * 3600L));
    assertThat(uso().getCreditosReservados()).isZero();
  }

  @Test
  @DisplayName("Virada da semana em Brasilia: domingo 23:59 x segunda 00:00, sem acumulo")
  void viradaDaSemana() {
    CotaUsoAssistenteService domingo = service(LocalDateTime.of(2026, 10, 18, 23, 59));
    CotaUsoAssistenteService segunda = service(LocalDateTime.of(2026, 10, 19, 0, 0));

    CotaUsoAssistenteService.Reserva r =
        domingo.reservar(TipoTitularCota.PRODUTOR, produtor, "ollama/qwen3");
    assertThat(r.semana()).isEqualTo(LocalDate.of(2026, 10, 12));
    // Acertada depois da meia-noite: vai para a semana em que foi reservada.
    segunda.acertar(r, 1000, 100, "ollama/qwen3");

    assertThat(segunda.reservar(TipoTitularCota.PRODUTOR, produtor, "ollama/qwen3").semana())
        .isEqualTo(LocalDate.of(2026, 10, 19));
    assertThat(segunda.consultar(TipoTitularCota.PRODUTOR, produtor).consumidos()).isZero();
    assertThat(repository.findById(new UsoSemanalAssistenteId(TipoTitularCota.PRODUTOR, produtor,
        LocalDate.of(2026, 10, 12))).orElseThrow().getCreditosConsumidos()).isEqualTo(2);
  }

  @Test
  @DisplayName("DoD #166: perguntas simultaneas do mesmo titular nao passam do limite")
  void reservaConcorrente() throws Exception {
    CotaUsoAssistenteService service = service(QUARTA);
    int perguntas = 12;
    ExecutorService pool = Executors.newFixedThreadPool(perguntas);
    CountDownLatch largada = new CountDownLatch(1);
    List<Future<Boolean>> resultados = new ArrayList<>();
    try {
      for (int i = 0; i < perguntas; i++) {
        Callable<Boolean> tentativa = () -> {
          largada.await();
          try {
            service.reservar(TipoTitularCota.PRODUTOR, produtor, "ollama/qwen3");
            return true;
          } catch (LimiteUsoAssistenteException e) {
            return false;
          }
        };
        resultados.add(pool.submit(tentativa));
      }
      largada.countDown();
      int aceitas = 0;
      for (Future<Boolean> resultado : resultados) {
        aceitas += resultado.get(30, TimeUnit.SECONDS) ? 1 : 0;
      }

      // 300 / 73 = 4 reservas cabem; as outras 8 recebem 429.
      assertThat(aceitas).isEqualTo(4);
      assertThat(uso().getCreditosReservados()).isEqualTo(4 * 73).isLessThanOrEqualTo(300);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName("Review: a pergunta nunca custa mais que o teto reservado")
  void consumoLimitadoAoTeto() {
    CotaUsoAssistenteService service = service(QUARTA);
    CotaUsoAssistenteService.Reserva r =
        service.reservar(TipoTitularCota.PRODUTOR, produtor, "ollama/qwen3");

    // Muito acima dos tetos por chamada (prompt enorme): cobra o teto, nao passa do limite.
    assertThat(service.acertar(r, 1_000_000, 50_000, "ollama/qwen3")).isEqualTo(73);
    assertThat(uso().getCreditosConsumidos()).isEqualTo(73);
  }

  @Test
  @DisplayName("Review: reserva presa (processo morto) volta ao saldo na proxima reserva")
  void reservaPresaLiberada() {
    propriedades.getCota().setCreditosPorSemanaProdutor(150);
    // Duas reservas que nunca acertaram, numa linha parada ha 1 hora (timeout padrao: 5 min).
    repository.save(UsoSemanalAssistente.builder()
        .id(new UsoSemanalAssistenteId(TipoTitularCota.PRODUTOR, produtor,
            LocalDate.of(2026, 10, 12)))
        .creditosReservados(146)
        .atualizadoEm(QUARTA.minusHours(1).atZone(ZONE).toOffsetDateTime())
        .build());
    CotaUsoAssistenteService service = service(QUARTA);

    assertThat(service.consultar(TipoTitularCota.PRODUTOR, produtor).restantes()).isEqualTo(150);
    service.reservar(TipoTitularCota.PRODUTOR, produtor, "ollama/qwen3");
    assertThat(uso().getCreditosReservados()).isEqualTo(73);
  }

  @Test
  @DisplayName("Reserva recente nao e liberada: outra pergunta pode estar em andamento")
  void reservaRecenteMantida() {
    propriedades.getCota().setCreditosPorSemanaProdutor(150);
    repository.save(UsoSemanalAssistente.builder()
        .id(new UsoSemanalAssistenteId(TipoTitularCota.PRODUTOR, produtor,
            LocalDate.of(2026, 10, 12)))
        .creditosReservados(146)
        .atualizadoEm(QUARTA.minusMinutes(2).atZone(ZONE).toOffsetDateTime())
        .build());
    CotaUsoAssistenteService service = service(QUARTA);

    assertThatThrownBy(() -> service.reservar(TipoTitularCota.PRODUTOR, produtor, "ollama/qwen3"))
        .isInstanceOf(LimiteUsoAssistenteException.class);
    assertThat(uso().getCreditosReservados()).isEqualTo(146);
  }

  @Test
  @DisplayName("Cota desligada: nada e reservado nem gravado")
  void cotaDesligada() {
    propriedades.getCota().setHabilitada(false);
    CotaUsoAssistenteService service = service(QUARTA);

    CotaUsoAssistenteService.Reserva r =
        service.reservar(TipoTitularCota.PRODUTOR, produtor, "ollama/qwen3");
    service.acertar(r, 1000, 100, "ollama/qwen3");

    assertThat(r.ativa()).isFalse();
    assertThat(repository.count()).isZero();
    assertThat(service.consultar(TipoTitularCota.PRODUTOR, produtor).habilitada()).isFalse();
  }

  private CotaUsoAssistenteService service(LocalDateTime agoraEmBrasilia) {
    Clock clock = Clock.fixed(agoraEmBrasilia.atZone(ZONE).toInstant(), ZONE);
    return new CotaUsoAssistenteService(repository, new PlanoUsoPorConfiguracao(propriedades),
        propriedades, clock, transactionManager);
  }

  private UsoSemanalAssistente uso() {
    return repository.findById(new UsoSemanalAssistenteId(TipoTitularCota.PRODUTOR, produtor,
        LocalDate.of(2026, 10, 12))).orElseThrow();
  }
}
