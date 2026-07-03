package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.dto.ApuracaoResponse;
import br.com.agrocontabil.exception.ApuracaoNaoReabrivelException;
import br.com.agrocontabil.exception.ApuracaoPeriodoFechadoException;
import br.com.agrocontabil.exception.ReaberturaForaPrazoException;
import br.com.agrocontabil.exception.ResourceNotFoundException;
import br.com.agrocontabil.model.ApuracaoMensal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusApuracao;
import br.com.agrocontabil.repository.ApuracaoMensalRepository;
import br.com.agrocontabil.repository.CreditoSaldoAgregado;
import br.com.agrocontabil.repository.DebitoSaldoAgregado;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Testes unitários do {@link ApuracaoService} (issue Agrocontabil#41 — RN004).
 *
 * <p>Isola a consolidação mensal com mocks das agregações já existentes no
 * {@link NotaFiscalRepository} (débito das saídas AUTORIZADAS e crédito das entradas IMPORTADAS),
 * do {@link ApuracaoMensalRepository} e do provedor de produtor autenticado. Verifica o cálculo
 * do saldo ({@code débito − crédito}), o recorte por produtor (anti-IDOR), a janela temporal do
 * período ({@code America/Sao_Paulo}), o upsert e o fechamento pelo job.
 */
@ExtendWith(MockitoExtension.class)
class ApuracaoServiceTest {

  @Mock private NotaFiscalRepository notaFiscalRepository;
  @Mock private ApuracaoMensalRepository apuracaoRepository;
  @Mock private ProdutorRepository produtorRepository;
  @Mock private AuthenticatedProdutorProvider produtorProvider;
  @Mock private org.springframework.data.domain.AuditorAware<String> auditorAware;

  private ApuracaoService service;

  @Captor private ArgumentCaptor<OffsetDateTime> inicioCaptor;
  @Captor private ArgumentCaptor<OffsetDateTime> fimCaptor;
  @Captor private ArgumentCaptor<ApuracaoMensal> apuracaoCaptor;

  private Produtor produtor;

  /**
   * Relógio fixo em 2026-07-15 (fuso America/Sao_Paulo) para tornar determinística a borda de 3
   * meses da RN005. Com "hoje" em 2026-07, os períodos permitidos são 2026-04..2026-07 (o 3º mês
   * retroativo, abril, é a borda inclusiva) e 2026-03 (4º mês) é negado.
   */
  private static final Clock CLOCK_FIXO = Clock.fixed(
      Instant.parse("2026-07-15T12:00:00Z"), ZoneId.of("America/Sao_Paulo"));

  @BeforeEach
  void setUp() {
    service = new ApuracaoService(
        notaFiscalRepository, apuracaoRepository, produtorRepository, produtorProvider,
        auditorAware, CLOCK_FIXO);
    produtor = Produtor.builder().id(UUID.randomUUID()).nomeCompleto("Produtor").build();
  }

  private ApuracaoMensal fechada(String periodo) {
    return ApuracaoMensal.builder()
        .id(UUID.randomUUID())
        .produtor(produtor)
        .periodo(periodo)
        .status(StatusApuracao.FECHADO)
        .debitoIbs(new BigDecimal("100.00"))
        .debitoCbs(new BigDecimal("50.00"))
        .creditoIbs(new BigDecimal("10.00"))
        .creditoCbs(new BigDecimal("5.00"))
        .saldoIbs(new BigDecimal("90.00"))
        .saldoCbs(new BigDecimal("45.00"))
        .totalNotas(3L)
        .dataFechamento(OffsetDateTime.parse("2026-07-31T23:00:00-03:00"))
        .build();
  }

  private ApuracaoMensal aberta(String periodo) {
    return ApuracaoMensal.builder()
        .id(UUID.randomUUID())
        .produtor(produtor)
        .periodo(periodo)
        .status(StatusApuracao.ABERTO)
        .build();
  }

  @Test
  @DisplayName("Reabre apuracao FECHADA dentro de 3 meses: volta a ABERTO com auditoria preenchida")
  void reabreDentroDoPrazo() {
    ApuracaoMensal apuracao = fechada("2026-06");
    when(produtorProvider.get()).thenReturn(produtor);
    when(auditorAware.getCurrentAuditor()).thenReturn(Optional.of(produtor.getId().toString()));
    when(apuracaoRepository.findByIdAndProdutorId(apuracao.getId(), produtor.getId()))
        .thenReturn(Optional.of(apuracao));
    when(apuracaoRepository.save(any(ApuracaoMensal.class))).thenAnswer(inv -> inv.getArgument(0));

    ApuracaoResponse resposta = service.reabrir(apuracao.getId());

    assertThat(resposta.status()).isEqualTo("ABERTO");
    verify(apuracaoRepository).save(apuracaoCaptor.capture());
    ApuracaoMensal salva = apuracaoCaptor.getValue();
    assertThat(salva.getStatus()).isEqualTo(StatusApuracao.ABERTO);
    assertThat(salva.getReabertoPor()).isEqualTo(produtor.getId().toString());
    assertThat(salva.getDataReabertura()).isNotNull();
  }

  @Test
  @DisplayName("Reabre o 3o mes retroativo (2026-04): borda inclusiva permitida")
  void reabreTerceiroMesRetroativoPermitido() {
    ApuracaoMensal apuracao = fechada("2026-04");
    when(produtorProvider.get()).thenReturn(produtor);
    when(apuracaoRepository.findByIdAndProdutorId(apuracao.getId(), produtor.getId()))
        .thenReturn(Optional.of(apuracao));
    when(apuracaoRepository.save(any(ApuracaoMensal.class))).thenAnswer(inv -> inv.getArgument(0));

    ApuracaoResponse resposta = service.reabrir(apuracao.getId());

    assertThat(resposta.status()).isEqualTo("ABERTO");
  }

  @Test
  @DisplayName("Reabertura do 4o mes retroativo (2026-03) fora do prazo: 422, nao persiste")
  void reaberturaQuartoMesRetroativoNegada() {
    ApuracaoMensal apuracao = fechada("2026-03");
    when(produtorProvider.get()).thenReturn(produtor);
    when(apuracaoRepository.findByIdAndProdutorId(apuracao.getId(), produtor.getId()))
        .thenReturn(Optional.of(apuracao));

    assertThatThrownBy(() -> service.reabrir(apuracao.getId()))
        .isInstanceOf(ReaberturaForaPrazoException.class);

    verify(apuracaoRepository, never()).save(any());
  }

  @Test
  @DisplayName("Reabertura de apuracao nao FECHADA (ABERTO) lanca 409, nao persiste")
  void reaberturaStatusInvalidoLanca409() {
    ApuracaoMensal aberta = fechada("2026-06");
    aberta.setStatus(StatusApuracao.ABERTO);
    when(produtorProvider.get()).thenReturn(produtor);
    when(apuracaoRepository.findByIdAndProdutorId(aberta.getId(), produtor.getId()))
        .thenReturn(Optional.of(aberta));

    assertThatThrownBy(() -> service.reabrir(aberta.getId()))
        .isInstanceOf(ApuracaoNaoReabrivelException.class);

    verify(apuracaoRepository, never()).save(any());
  }

  @Test
  @DisplayName("Reabertura de apuracao de outro produtor (ou inexistente) retorna 404")
  void reaberturaDeOutroProdutorRetorna404() {
    UUID id = UUID.randomUUID();
    when(produtorProvider.get()).thenReturn(produtor);
    when(apuracaoRepository.findByIdAndProdutorId(id, produtor.getId()))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.reabrir(id))
        .isInstanceOf(ResourceNotFoundException.class);

    verify(apuracaoRepository, never()).save(any());
  }

  @Test
  @DisplayName("Consolida saldo = debito - credito por IBS e CBS do produtor autenticado")
  void consolidaSaldoDebitoMenosCredito() {
    when(produtorProvider.get()).thenReturn(produtor);
    when(notaFiscalRepository.somarDebitosPorPeriodo(eq(produtor.getId()), inicioCaptor.capture(),
        fimCaptor.capture()))
        .thenReturn(new DebitoSaldoAgregado(new BigDecimal("150.00"), new BigDecimal("1350.00"),
            5L));
    when(notaFiscalRepository.somarCreditosPorPeriodo(eq(produtor.getId()), any(), any()))
        .thenReturn(new CreditoSaldoAgregado(new BigDecimal("30.00"), new BigDecimal("270.00"),
            3L));
    when(apuracaoRepository.findByProdutorIdAndPeriodo(produtor.getId(), "2026-07"))
        .thenReturn(Optional.empty());
    when(apuracaoRepository.save(any(ApuracaoMensal.class))).thenAnswer(inv -> inv.getArgument(0));

    ApuracaoResponse resposta = service.consolidar("2026-07");

    assertThat(resposta.periodo()).isEqualTo("2026-07");
    assertThat(resposta.status()).isEqualTo("ABERTO");
    assertThat(resposta.debitoIbs()).isEqualByComparingTo("150.00");
    assertThat(resposta.debitoCbs()).isEqualByComparingTo("1350.00");
    assertThat(resposta.creditoIbs()).isEqualByComparingTo("30.00");
    assertThat(resposta.creditoCbs()).isEqualByComparingTo("270.00");
    assertThat(resposta.saldoIbs()).isEqualByComparingTo("120.00");
    assertThat(resposta.saldoCbs()).isEqualByComparingTo("1080.00");
    assertThat(resposta.totalNotas()).isEqualTo(8L);
    assertThat(resposta.dataFechamento()).isNull();

    // Janela [inicio, fim) do mes em America/Sao_Paulo (-03:00).
    assertThat(inicioCaptor.getValue()).isEqualTo(OffsetDateTime.parse("2026-07-01T00:00:00-03:00"));
    assertThat(fimCaptor.getValue()).isEqualTo(OffsetDateTime.parse("2026-08-01T00:00:00-03:00"));
  }

  @Test
  @DisplayName("Periodo sem notas consolida saldo zerado (nulls do SUM viram 0.00)")
  void periodoSemNotasConsolidaZero() {
    when(produtorProvider.get()).thenReturn(produtor);
    when(notaFiscalRepository.somarDebitosPorPeriodo(any(), any(), any()))
        .thenReturn(new DebitoSaldoAgregado(null, null, 0L));
    when(notaFiscalRepository.somarCreditosPorPeriodo(any(), any(), any()))
        .thenReturn(new CreditoSaldoAgregado(null, null, 0L));
    when(apuracaoRepository.findByProdutorIdAndPeriodo(any(), eq("2026-01")))
        .thenReturn(Optional.empty());
    when(apuracaoRepository.save(any(ApuracaoMensal.class))).thenAnswer(inv -> inv.getArgument(0));

    ApuracaoResponse resposta = service.consolidar("2026-01");

    assertThat(resposta.debitoIbs()).isEqualByComparingTo("0.00");
    assertThat(resposta.creditoIbs()).isEqualByComparingTo("0.00");
    assertThat(resposta.saldoIbs()).isEqualByComparingTo("0.00");
    assertThat(resposta.saldoCbs()).isEqualByComparingTo("0.00");
    assertThat(resposta.totalNotas()).isZero();
  }

  @Test
  @DisplayName("Consolidacao existente (ABERTO) e atualizada, nao duplicada")
  void consolidacaoExistenteEhAtualizada() {
    ApuracaoMensal existente = ApuracaoMensal.builder()
        .id(UUID.randomUUID())
        .produtor(produtor)
        .periodo("2026-07")
        .status(StatusApuracao.ABERTO)
        .debitoIbs(new BigDecimal("10.00"))
        .build();
    when(produtorProvider.get()).thenReturn(produtor);
    when(notaFiscalRepository.somarDebitosPorPeriodo(any(), any(), any()))
        .thenReturn(new DebitoSaldoAgregado(new BigDecimal("200.00"), new BigDecimal("100.00"), 4L));
    when(notaFiscalRepository.somarCreditosPorPeriodo(any(), any(), any()))
        .thenReturn(new CreditoSaldoAgregado(new BigDecimal("50.00"), new BigDecimal("20.00"), 2L));
    when(apuracaoRepository.findByProdutorIdAndPeriodo(produtor.getId(), "2026-07"))
        .thenReturn(Optional.of(existente));
    when(apuracaoRepository.save(any(ApuracaoMensal.class))).thenAnswer(inv -> inv.getArgument(0));

    service.consolidar("2026-07");

    verify(apuracaoRepository).save(apuracaoCaptor.capture());
    ApuracaoMensal salva = apuracaoCaptor.getValue();
    assertThat(salva.getId()).isEqualTo(existente.getId());
    assertThat(salva.getDebitoIbs()).isEqualByComparingTo("200.00");
    assertThat(salva.getSaldoIbs()).isEqualByComparingTo("150.00");
    assertThat(salva.getTotalNotas()).isEqualTo(6L);
  }

  @Test
  @DisplayName("Consolidacao usa o ID do produtor autenticado nas duas agregacoes (anti-IDOR)")
  void consolidacaoRecortaPorProdutorAutenticado() {
    when(produtorProvider.get()).thenReturn(produtor);
    when(notaFiscalRepository.somarDebitosPorPeriodo(any(), any(), any()))
        .thenReturn(new DebitoSaldoAgregado(BigDecimal.ZERO, BigDecimal.ZERO, 0L));
    when(notaFiscalRepository.somarCreditosPorPeriodo(any(), any(), any()))
        .thenReturn(new CreditoSaldoAgregado(BigDecimal.ZERO, BigDecimal.ZERO, 0L));
    when(apuracaoRepository.findByProdutorIdAndPeriodo(any(), any())).thenReturn(Optional.empty());
    when(apuracaoRepository.save(any(ApuracaoMensal.class))).thenAnswer(inv -> inv.getArgument(0));

    service.consolidar("2026-07");

    ArgumentCaptor<UUID> debitoId = ArgumentCaptor.forClass(UUID.class);
    ArgumentCaptor<UUID> creditoId = ArgumentCaptor.forClass(UUID.class);
    verify(notaFiscalRepository).somarDebitosPorPeriodo(debitoId.capture(), any(), any());
    verify(notaFiscalRepository).somarCreditosPorPeriodo(creditoId.capture(), any(), any());
    assertThat(debitoId.getValue()).isEqualTo(produtor.getId());
    assertThat(creditoId.getValue()).isEqualTo(produtor.getId());
  }

  @Test
  @DisplayName("Consolidar (ABERTO) sobre periodo FECHADO lanca 409 sem agregar nem persistir (issue #54)")
  void consolidarSobreFechadoLanca409() {
    ApuracaoMensal fechada = fechada("2026-07");
    when(produtorProvider.get()).thenReturn(produtor);
    when(apuracaoRepository.findByProdutorIdAndPeriodo(produtor.getId(), "2026-07"))
        .thenReturn(Optional.of(fechada));

    assertThatThrownBy(() -> service.consolidar("2026-07"))
        .isInstanceOf(ApuracaoPeriodoFechadoException.class);

    // Rejeicao precoce: nao rebaixa status, nao persiste e nem sequer agrega notas.
    verify(apuracaoRepository, never()).save(any());
    verifyNoInteractions(notaFiscalRepository);
  }

  @Test
  @DisplayName("Job re-fecha periodo ja FECHADO (alvo FECHADO) sem lancar 409 (issue #54)")
  void jobRefechaPeriodoJaFechado() {
    ApuracaoMensal fechada = fechada("2026-07");
    when(produtorRepository.findAll()).thenReturn(List.of(produtor));
    when(notaFiscalRepository.somarDebitosPorPeriodo(any(), any(), any()))
        .thenReturn(new DebitoSaldoAgregado(new BigDecimal("100.00"), new BigDecimal("50.00"), 2L));
    when(notaFiscalRepository.somarCreditosPorPeriodo(any(), any(), any()))
        .thenReturn(new CreditoSaldoAgregado(new BigDecimal("10.00"), new BigDecimal("5.00"), 1L));
    when(apuracaoRepository.findByProdutorIdAndPeriodo(produtor.getId(), "2026-07"))
        .thenReturn(Optional.of(fechada));
    when(apuracaoRepository.save(any(ApuracaoMensal.class))).thenAnswer(inv -> inv.getArgument(0));

    int fechadas = service.fecharPeriodo("2026-07");

    assertThat(fechadas).isEqualTo(1);
    verify(apuracaoRepository).save(apuracaoCaptor.capture());
    assertThat(apuracaoCaptor.getValue().getStatus()).isEqualTo(StatusApuracao.FECHADO);
  }

  @Test
  @DisplayName("Refecha apuracoes ABERTAS de meses anteriores na janela RN005 (issue #57)")
  void refechaAbertasDeMesesAnterioresNaJanela() {
    ApuracaoMensal abril = aberta("2026-04");
    ApuracaoMensal maio = aberta("2026-05");
    when(apuracaoRepository.findByStatusAndPeriodoGreaterThanEqualAndPeriodoLessThan(
        StatusApuracao.ABERTO, "2026-04", "2026-07"))
        .thenReturn(List.of(abril, maio));
    when(notaFiscalRepository.somarDebitosPorPeriodo(any(), any(), any()))
        .thenReturn(new DebitoSaldoAgregado(new BigDecimal("100.00"), new BigDecimal("50.00"), 2L));
    when(notaFiscalRepository.somarCreditosPorPeriodo(any(), any(), any()))
        .thenReturn(new CreditoSaldoAgregado(new BigDecimal("10.00"), new BigDecimal("5.00"), 1L));
    when(apuracaoRepository.findByProdutorIdAndPeriodo(produtor.getId(), "2026-04"))
        .thenReturn(Optional.of(abril));
    when(apuracaoRepository.findByProdutorIdAndPeriodo(produtor.getId(), "2026-05"))
        .thenReturn(Optional.of(maio));
    when(apuracaoRepository.save(any(ApuracaoMensal.class))).thenAnswer(inv -> inv.getArgument(0));

    int refechadas = service.refecharPeriodosReabertos();

    assertThat(refechadas).isEqualTo(2);
    verify(apuracaoRepository, org.mockito.Mockito.times(2)).save(apuracaoCaptor.capture());
    assertThat(apuracaoCaptor.getAllValues()).allSatisfy(a -> {
      assertThat(a.getStatus()).isEqualTo(StatusApuracao.FECHADO);
      // CLOCK_FIXO = 2026-07-15T12:00:00Z; em America/Sao_Paulo (-03:00) => 2026-07-15T09:00:00-03:00.
      assertThat(a.getDataFechamento())
          .isEqualTo(OffsetDateTime.parse("2026-07-15T09:00:00-03:00"));
    });
  }

  @Test
  @DisplayName("Refechamento consulta a janela RN005 [mesCorrente-3, mesCorrente) por ABERTO (issue #57)")
  void refecharConsultaJanelaRn005SemAbertasRetornaZero() {
    when(apuracaoRepository.findByStatusAndPeriodoGreaterThanEqualAndPeriodoLessThan(
        any(), any(), any())).thenReturn(List.of());

    int refechadas = service.refecharPeriodosReabertos();

    assertThat(refechadas).isZero();
    // Janela de refechamento = mesma janela da reabertura (RN005): [2026-04, 2026-07).
    verify(apuracaoRepository).findByStatusAndPeriodoGreaterThanEqualAndPeriodoLessThan(
        StatusApuracao.ABERTO, "2026-04", "2026-07");
    verify(apuracaoRepository, never()).save(any());
    verifyNoInteractions(notaFiscalRepository);
  }

  @Test
  @DisplayName("Consultar retorna a apuracao persistida do produtor sem recalcular")
  void consultarRetornaApuracaoPersistida() {
    ApuracaoMensal persistida = ApuracaoMensal.builder()
        .produtor(produtor)
        .periodo("2026-07")
        .status(StatusApuracao.FECHADO)
        .debitoIbs(new BigDecimal("150.00"))
        .debitoCbs(new BigDecimal("1350.00"))
        .creditoIbs(new BigDecimal("30.00"))
        .creditoCbs(new BigDecimal("270.00"))
        .saldoIbs(new BigDecimal("120.00"))
        .saldoCbs(new BigDecimal("1080.00"))
        .totalNotas(8L)
        .dataFechamento(OffsetDateTime.parse("2026-07-31T23:00:00-03:00"))
        .build();
    when(produtorProvider.get()).thenReturn(produtor);
    when(apuracaoRepository.findByProdutorIdAndPeriodo(produtor.getId(), "2026-07"))
        .thenReturn(Optional.of(persistida));

    ApuracaoResponse resposta = service.consultar("2026-07");

    assertThat(resposta.status()).isEqualTo("FECHADO");
    assertThat(resposta.saldoIbs()).isEqualByComparingTo("120.00");
    assertThat(resposta.dataFechamento()).isNotNull();
    // Consulta nao recalcula agregacoes.
    verify(notaFiscalRepository, never()).somarDebitosPorPeriodo(any(), any(), any());
  }

  @Test
  @DisplayName("Consultar periodo nao consolidado projeta ao vivo das notas, sem persistir (issue #53)")
  void consultarPeriodoNaoConsolidadoProjetaAoVivo() {
    when(produtorProvider.get()).thenReturn(produtor);
    when(apuracaoRepository.findByProdutorIdAndPeriodo(produtor.getId(), "2026-07"))
        .thenReturn(Optional.empty());
    when(notaFiscalRepository.somarDebitosPorPeriodo(eq(produtor.getId()), inicioCaptor.capture(),
        fimCaptor.capture()))
        .thenReturn(new DebitoSaldoAgregado(new BigDecimal("150.00"), new BigDecimal("1350.00"),
            5L));
    when(notaFiscalRepository.somarCreditosPorPeriodo(eq(produtor.getId()), any(), any()))
        .thenReturn(new CreditoSaldoAgregado(new BigDecimal("30.00"), new BigDecimal("270.00"),
            3L));

    ApuracaoResponse resposta = service.consultar("2026-07");

    assertThat(resposta.periodo()).isEqualTo("2026-07");
    // Projecao ao vivo nunca e persistida: fica sempre em ABERTO e sem data de fechamento.
    assertThat(resposta.status()).isEqualTo("ABERTO");
    assertThat(resposta.dataFechamento()).isNull();
    assertThat(resposta.debitoIbs()).isEqualByComparingTo("150.00");
    assertThat(resposta.debitoCbs()).isEqualByComparingTo("1350.00");
    assertThat(resposta.creditoIbs()).isEqualByComparingTo("30.00");
    assertThat(resposta.creditoCbs()).isEqualByComparingTo("270.00");
    assertThat(resposta.saldoIbs()).isEqualByComparingTo("120.00");
    assertThat(resposta.saldoCbs()).isEqualByComparingTo("1080.00");
    assertThat(resposta.totalNotas()).isEqualTo(8L);

    // Janela [inicio, fim) do mes em America/Sao_Paulo (-03:00), igual a consolidacao.
    assertThat(inicioCaptor.getValue()).isEqualTo(OffsetDateTime.parse("2026-07-01T00:00:00-03:00"));
    assertThat(fimCaptor.getValue()).isEqualTo(OffsetDateTime.parse("2026-08-01T00:00:00-03:00"));
    // Consulta jamais persiste (nao cria/atualiza ApuracaoMensal).
    verify(apuracaoRepository, never()).save(any());
  }

  @Test
  @DisplayName("Consultar periodo nao consolidado e sem notas projeta saldo zerado ABERTO (issue #53)")
  void consultarPeriodoNaoConsolidadoSemNotasProjetaZerado() {
    when(produtorProvider.get()).thenReturn(produtor);
    when(apuracaoRepository.findByProdutorIdAndPeriodo(produtor.getId(), "2026-05"))
        .thenReturn(Optional.empty());
    when(notaFiscalRepository.somarDebitosPorPeriodo(any(), any(), any()))
        .thenReturn(new DebitoSaldoAgregado(null, null, 0L));
    when(notaFiscalRepository.somarCreditosPorPeriodo(any(), any(), any()))
        .thenReturn(new CreditoSaldoAgregado(null, null, 0L));

    ApuracaoResponse resposta = service.consultar("2026-05");

    assertThat(resposta.periodo()).isEqualTo("2026-05");
    assertThat(resposta.status()).isEqualTo("ABERTO");
    assertThat(resposta.saldoIbs()).isEqualByComparingTo("0.00");
    assertThat(resposta.saldoCbs()).isEqualByComparingTo("0.00");
    assertThat(resposta.totalNotas()).isZero();
    verify(apuracaoRepository, never()).save(any());
  }

  @Test
  @DisplayName("Periodo invalido lanca IllegalArgumentException (400) sem tocar no banco")
  void periodoInvalidoLancaExcecao() {
    assertThatThrownBy(() -> service.consolidar("2026-13"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.consultar("julho/2026"))
        .isInstanceOf(IllegalArgumentException.class);

    verifyNoInteractions(notaFiscalRepository);
    verifyNoInteractions(apuracaoRepository);
  }

  @Test
  @DisplayName("Job consolida e fecha (FECHADO) o periodo de todos os produtores")
  void jobFechaPeriodoDeTodosProdutores() {
    Produtor p2 = Produtor.builder().id(UUID.randomUUID()).nomeCompleto("Outro").build();
    when(produtorRepository.findAll()).thenReturn(List.of(produtor, p2));
    when(notaFiscalRepository.somarDebitosPorPeriodo(any(), any(), any()))
        .thenReturn(new DebitoSaldoAgregado(new BigDecimal("100.00"), new BigDecimal("50.00"), 2L));
    when(notaFiscalRepository.somarCreditosPorPeriodo(any(), any(), any()))
        .thenReturn(new CreditoSaldoAgregado(new BigDecimal("10.00"), new BigDecimal("5.00"), 1L));
    when(apuracaoRepository.findByProdutorIdAndPeriodo(any(), eq("2026-07")))
        .thenReturn(Optional.empty());
    when(apuracaoRepository.save(any(ApuracaoMensal.class))).thenAnswer(inv -> inv.getArgument(0));

    int fechadas = service.fecharPeriodo("2026-07");

    assertThat(fechadas).isEqualTo(2);
    verify(apuracaoRepository, org.mockito.Mockito.times(2)).save(apuracaoCaptor.capture());
    List<ApuracaoMensal> salvas = apuracaoCaptor.getAllValues();
    assertThat(salvas).allSatisfy(a -> {
      assertThat(a.getStatus()).isEqualTo(StatusApuracao.FECHADO);
      assertThat(a.getDataFechamento()).isNotNull();
      assertThat(a.getSaldoIbs()).isEqualByComparingTo("90.00");
    });
    // O job nao usa o produtor autenticado (roda sem contexto de seguranca).
    verifyNoInteractions(produtorProvider);
  }

  @Test
  @DisplayName("Fechamento carimba dataFechamento a partir do Clock injetado (determinismo — issue #56)")
  void fechamentoUsaClockInjetado() {
    when(produtorRepository.findAll()).thenReturn(List.of(produtor));
    when(notaFiscalRepository.somarDebitosPorPeriodo(any(), any(), any()))
        .thenReturn(new DebitoSaldoAgregado(new BigDecimal("100.00"), new BigDecimal("50.00"), 2L));
    when(notaFiscalRepository.somarCreditosPorPeriodo(any(), any(), any()))
        .thenReturn(new CreditoSaldoAgregado(new BigDecimal("10.00"), new BigDecimal("5.00"), 1L));
    when(apuracaoRepository.findByProdutorIdAndPeriodo(any(), eq("2026-07")))
        .thenReturn(Optional.empty());
    when(apuracaoRepository.save(any(ApuracaoMensal.class))).thenAnswer(inv -> inv.getArgument(0));

    service.fecharPeriodo("2026-07");

    verify(apuracaoRepository).save(apuracaoCaptor.capture());
    // CLOCK_FIXO = 2026-07-15T12:00:00Z; em America/Sao_Paulo (-03:00) => 2026-07-15T09:00:00-03:00.
    assertThat(apuracaoCaptor.getValue().getDataFechamento())
        .isEqualTo(OffsetDateTime.parse("2026-07-15T09:00:00-03:00"));
  }
}
