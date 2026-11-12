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
    propriedades.getCota().setCreditosPorSemanaProdutor(300_000);
    produtor = UUID.randomUUID();
  }

  @Test
  @DisplayName("Creditos: saida pesa mais, fator do modelo multiplica, arredonda para cima")
  void calculoDeCreditos() {
    propriedades.getCota().getFatorPorModelo().put("openai/gpt-4o", 3.0);
    CotaUsoAssistenteService service = service(QUARTA);

    // 1 credito por token ponderado (#511).
    // Pergunta simples: 1.000 de entrada + 100 de saida = 1.400 ponderados = 1.400 creditos.
    assertThat(service.creditos(1000, 100, "ollama/qwen3")).isEqualTo(1400);
    // Pergunta complexa (rodadas de funcao, trechos de lei): 12.000 + 800 x 4 = 15.200.
    assertThat(service.creditos(12_000, 800, "ollama/qwen3")).isEqualTo(15_200);
    // Modelo maior: mesmo consumo, fator 3.
    assertThat(service.creditos(1000, 100, "openai/gpt-4o")).isEqualTo(4200);
    // Leitura de um "ola" (1 token, sem saida): 1 credito, nunca zero.
    assertThat(service.creditos(1, 0, "ollama/qwen3")).isEqualTo(1);
    assertThat(service.creditos(0, 0, "ollama/qwen3")).isZero();
    // Teto (#270): 3 chamadas x 8.192 de entrada + (256 + 512 + 1.024) de saida x 4 = 31.744.
    assertThat(service.tetoDaPergunta("ollama/qwen3")).isEqualTo(31_744);
    // Mesmo teto com fator 3.
    assertThat(service.tetoDaPergunta("openai/gpt-4o")).isEqualTo(95_232);
  }

  @Test
  @DisplayName("Acerto debita o real e devolve a diferenca; estorno devolve tudo")
  void acertoEEstorno() {
    CotaUsoAssistenteService service = service(QUARTA);

    CotaUsoAssistenteService.Reserva primeira =
        service.reservar(TipoTitularCota.PRODUTOR, produtor, "ollama/qwen3");
    assertThat(uso().getCreditosReservados()).isEqualTo(31_744);
    assertThat(service.acertar(primeira, 1000, 100, "ollama/qwen3")).isEqualTo(1400);
    assertThat(uso().getCreditosReservados()).isZero();
    assertThat(uso().getCreditosConsumidos()).isEqualTo(1400);
    assertThat(uso().getTokensEntrada()).isEqualTo(1000);
    assertThat(uso().getPerguntas()).isEqualTo(1);

    CotaUsoAssistenteService.Reserva segunda =
        service.reservar(TipoTitularCota.PRODUTOR, produtor, "ollama/qwen3");
    service.estornar(segunda);
    // Estorno repetido nao vira credito de graca: a reserva ja nao esta la.
    service.estornar(segunda);
    assertThat(uso().getCreditosReservados()).isZero();
    assertThat(uso().getCreditosConsumidos()).isEqualTo(1400);

    CotaUsoAssistenteService.Uso consulta = service.consultar(TipoTitularCota.PRODUTOR, produtor);
    assertThat(consulta.consumidos()).isEqualTo(1400);
    assertThat(consulta.restantes()).isEqualTo(298_600);
    assertThat(consulta.renovaEm().toLocalDate()).isEqualTo(LocalDate.of(2026, 10, 19));
  }

  @Test
  @DisplayName("Sem saldo para o teto: 429 com Retry-After ate a segunda 00:00 de Brasilia")
  void semSaldo() {
    repository.save(UsoSemanalAssistente.builder()
        .id(new UsoSemanalAssistenteId(TipoTitularCota.PRODUTOR, produtor,
            LocalDate.of(2026, 10, 12)))
        .creditosConsumidos(270_000)
        .atualizadoEm(QUARTA.atZone(ZONE).toOffsetDateTime())
        .build());
    CotaUsoAssistenteService service = service(QUARTA);

    // 270.000 consumidos + 31.744 de teto > 300.000.
    assertThatThrownBy(() -> service.reservar(TipoTitularCota.PRODUTOR, produtor, "ollama/qwen3"))
        .isInstanceOfSatisfying(LimiteUsoAssistenteException.class, e ->
            // Quarta 10h ate segunda 00h: 4 dias e 14 horas.
            assertThat(e.retryAfterSegundos()).isEqualTo((4 * 24 + 14) * 3600L));
    assertThat(uso().getCreditosReservados()).isZero();
  }

  @Test
  @DisplayName("#268: explicacao com reserva propria, acertada pelo uso real sem contar outra "
      + "pergunta; sem saldo, vazio e nunca 429")
  void reservaDaExplicacao() {
    CotaUsoAssistenteService service = service(QUARTA);
    // Uma chamada: 8.192 de entrada + 256 de saida x 4 = 9.216.
    assertThat(service.tetoDaExplicacao("ollama/qwen3")).isEqualTo(9_216);

    CotaUsoAssistenteService.Reserva pergunta =
        service.reservar(TipoTitularCota.PRODUTOR, produtor, "ollama/qwen3");
    service.acertar(pergunta, 1000, 100, "ollama/qwen3");
    CotaUsoAssistenteService.Reserva explicacao = service.reservarExplicacao(
        TipoTitularCota.PRODUTOR, produtor, "ollama/qwen3").orElseThrow();
    assertThat(explicacao.creditos()).isEqualTo(9_216);
    assertThat(uso().getCreditosReservados()).isEqualTo(9_216);

    assertThat(service.acertarExplicacao(explicacao, 600, 50, "ollama/qwen3")).isEqualTo(800);
    assertThat(uso().getCreditosReservados()).isZero();
    assertThat(uso().getCreditosConsumidos()).isEqualTo(1400 + 800);
    assertThat(uso().getTokensEntrada()).isEqualTo(1600);
    // A explicacao nao e outra pergunta.
    assertThat(uso().getPerguntas()).isEqualTo(1);

    // Saldo menor que o teto da explicacao: vazio, sem excecao e sem reservar nada.
    UUID semSaldo = UUID.randomUUID();
    UsoSemanalAssistenteId linha = new UsoSemanalAssistenteId(TipoTitularCota.PRODUTOR, semSaldo,
        LocalDate.of(2026, 10, 12));
    repository.save(UsoSemanalAssistente.builder().id(linha).creditosConsumidos(300_000 - 9_000)
        .atualizadoEm(QUARTA.atZone(ZONE).toOffsetDateTime()).build());
    assertThat(service.reservarExplicacao(TipoTitularCota.PRODUTOR, semSaldo, "ollama/qwen3"))
        .isEmpty();
    assertThat(repository.findById(linha).orElseThrow().getCreditosReservados()).isZero();
  }

  @Test
  @DisplayName("#268: com a cota desligada, a reserva da explicacao e inativa e nao mexe na linha")
  void reservaDaExplicacaoComCotaDesligada() {
    propriedades.getCota().setHabilitada(false);
    CotaUsoAssistenteService service = service(QUARTA);

    CotaUsoAssistenteService.Reserva reserva = service.reservarExplicacao(
        TipoTitularCota.PRODUTOR, produtor, "ollama/qwen3").orElseThrow();

    assertThat(reserva.ativa()).isFalse();
    assertThat(service.acertarExplicacao(reserva, 600, 50, "ollama/qwen3")).isZero();
    assertThat(repository.count()).isZero();
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
        LocalDate.of(2026, 10, 12))).orElseThrow().getCreditosConsumidos()).isEqualTo(1400);
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

      // 300.000 / 31.744 = 9 reservas cabem; as outras 3 recebem 429.
      assertThat(aceitas).isEqualTo(9);
      assertThat(uso().getCreditosReservados()).isEqualTo(9 * 31_744L)
          .isLessThanOrEqualTo(300_000);
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
    assertThat(service.acertar(r, 1_000_000, 50_000, "ollama/qwen3")).isEqualTo(31_744);
    assertThat(uso().getCreditosConsumidos()).isEqualTo(31_744);
  }

  @Test
  @DisplayName("Review: reserva presa (processo morto) volta ao saldo na proxima reserva")
  void reservaPresaLiberada() {
    propriedades.getCota().setCreditosPorSemanaProdutor(150_000);
    // Duas reservas que nunca acertaram, numa linha parada ha 1 hora (timeout padrao: 5 min).
    repository.save(UsoSemanalAssistente.builder()
        .id(new UsoSemanalAssistenteId(TipoTitularCota.PRODUTOR, produtor,
            LocalDate.of(2026, 10, 12)))
        .creditosReservados(146_000)
        .atualizadoEm(QUARTA.minusHours(1).atZone(ZONE).toOffsetDateTime())
        .build());
    CotaUsoAssistenteService service = service(QUARTA);

    assertThat(service.consultar(TipoTitularCota.PRODUTOR, produtor).restantes())
        .isEqualTo(150_000);
    service.reservar(TipoTitularCota.PRODUTOR, produtor, "ollama/qwen3");
    assertThat(uso().getCreditosReservados()).isEqualTo(31_744);
  }

  @Test
  @DisplayName("Reserva recente nao e liberada: outra pergunta pode estar em andamento")
  void reservaRecenteMantida() {
    propriedades.getCota().setCreditosPorSemanaProdutor(150_000);
    repository.save(UsoSemanalAssistente.builder()
        .id(new UsoSemanalAssistenteId(TipoTitularCota.PRODUTOR, produtor,
            LocalDate.of(2026, 10, 12)))
        .creditosReservados(146_000)
        .atualizadoEm(QUARTA.minusMinutes(2).atZone(ZONE).toOffsetDateTime())
        .build());
    CotaUsoAssistenteService service = service(QUARTA);

    assertThatThrownBy(() -> service.reservar(TipoTitularCota.PRODUTOR, produtor, "ollama/qwen3"))
        .isInstanceOf(LimiteUsoAssistenteException.class);
    assertThat(uso().getCreditosReservados()).isEqualTo(146_000);
  }

  @Test
  @DisplayName("#511: a leitura reserva so o custo da mensagem, e o acerto debita esse custo")
  void reservaDaLeitura() {
    propriedades.getCota().getFatorPorModelo().put("openai/gpt-4o", 3.0);
    CotaUsoAssistenteService service = service(QUARTA);

    CotaUsoAssistenteService.Reserva ola =
        service.reservarLeitura(TipoTitularCota.PRODUTOR, produtor, "ollama/qwen3", 1);
    assertThat(ola.ativa()).isTrue();
    assertThat(uso().getCreditosReservados()).isEqualTo(1);
    assertThat(service.acertar(ola, 1, 0, "ollama/qwen3")).isEqualTo(1);

    // Fator do modelo vale também na leitura: 5 tokens x 3.
    CotaUsoAssistenteService.Reserva pergunta =
        service.reservarLeitura(TipoTitularCota.PRODUTOR, produtor, "openai/gpt-4o", 5);
    assertThat(uso().getCreditosReservados()).isEqualTo(15);
    assertThat(service.acertar(pergunta, 5, 0, "openai/gpt-4o")).isEqualTo(15);

    assertThat(uso().getCreditosReservados()).isZero();
    assertThat(uso().getCreditosConsumidos()).isEqualTo(16);
    assertThat(uso().getPerguntas()).isEqualTo(2);
  }

  @Test
  @DisplayName("#511: sem saldo para a leitura, 429 também; com saldo só para ela, passa")
  void leituraSemSaldo() {
    repository.save(UsoSemanalAssistente.builder()
        .id(new UsoSemanalAssistenteId(TipoTitularCota.PRODUTOR, produtor,
            LocalDate.of(2026, 10, 12)))
        .creditosConsumidos(299_995)
        .atualizadoEm(QUARTA.atZone(ZONE).toOffsetDateTime())
        .build());
    CotaUsoAssistenteService service = service(QUARTA);

    // 5 restantes: o teto de uma pergunta de modelo não cabe, a leitura de 5 tokens cabe.
    assertThatThrownBy(() -> service.reservar(TipoTitularCota.PRODUTOR, produtor, "ollama/qwen3"))
        .isInstanceOf(LimiteUsoAssistenteException.class);
    service.reservarLeitura(TipoTitularCota.PRODUTOR, produtor, "ollama/qwen3", 5);
    assertThatThrownBy(() -> service.reservarLeitura(TipoTitularCota.PRODUTOR, produtor,
        "ollama/qwen3", 1)).isInstanceOf(LimiteUsoAssistenteException.class);
    assertThat(uso().getCreditosReservados()).isEqualTo(5);
  }

  @Test
  @DisplayName("#511: percentual usado nos limites: 0 so sem uso, 1 com qualquer uso, 99 com saldo, "
      + "100 so sem saldo")
  void percentualUsadoNosLimites() {
    // Sem uso nenhum.
    assertThat(CotaUsoAssistenteService.percentualUsado(0, 1_000_000, 1_000_000)).isZero();
    // Qualquer uso aparece: 1 crédito de 1.000.000 (um "olá") = 1%.
    assertThat(CotaUsoAssistenteService.percentualUsado(1, 1_000_000, 999_999)).isEqualTo(1);
    // Arredonda para cima: 120.001 de 1.000.000 = 12,0001% -> 13%.
    assertThat(CotaUsoAssistenteService.percentualUsado(120_000, 1_000_000, 880_000))
        .isEqualTo(12);
    assertThat(CotaUsoAssistenteService.percentualUsado(120_001, 1_000_000, 879_999))
        .isEqualTo(13);
    // Com saldo, nunca 100: falta 1 crédito -> 99%.
    assertThat(CotaUsoAssistenteService.percentualUsado(999_999, 1_000_000, 1)).isEqualTo(99);
    assertThat(CotaUsoAssistenteService.percentualUsado(990_001, 1_000_000, 9_999)).isEqualTo(99);
    // 100 só sem saldo: inteiro gasto, ou o resto preso em perguntas em andamento.
    assertThat(CotaUsoAssistenteService.percentualUsado(1_000_000, 1_000_000, 0)).isEqualTo(100);
    assertThat(CotaUsoAssistenteService.percentualUsado(990_000, 1_000_000, 0)).isEqualTo(100);
  }

  @Test
  @DisplayName("#511: a consulta devolve o percentual usado da semana")
  void consultaComPercentual() {
    CotaUsoAssistenteService service = service(QUARTA);
    assertThat(service.consultar(TipoTitularCota.PRODUTOR, produtor).percentualUsado()).isZero();

    CotaUsoAssistenteService.Reserva leitura =
        service.reservarLeitura(TipoTitularCota.PRODUTOR, produtor, "ollama/qwen3", 1);
    service.acertar(leitura, 1, 0, "ollama/qwen3");

    CotaUsoAssistenteService.Uso uso = service.consultar(TipoTitularCota.PRODUTOR, produtor);
    assertThat(uso.consumidos()).isEqualTo(1);
    assertThat(uso.percentualUsado()).isEqualTo(1);
  }

  @Test
  @DisplayName("Cota desligada: nada e reservado nem gravado")
  void cotaDesligada() {
    propriedades.getCota().setHabilitada(false);
    CotaUsoAssistenteService service = service(QUARTA);

    CotaUsoAssistenteService.Reserva r =
        service.reservar(TipoTitularCota.PRODUTOR, produtor, "ollama/qwen3");
    service.acertar(r, 1000, 100, "ollama/qwen3");
    CotaUsoAssistenteService.Reserva leitura =
        service.reservarLeitura(TipoTitularCota.PRODUTOR, produtor, "ollama/qwen3", 5);
    service.acertar(leitura, 5, 0, "ollama/qwen3");

    assertThat(r.ativa()).isFalse();
    assertThat(leitura.ativa()).isFalse();
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
