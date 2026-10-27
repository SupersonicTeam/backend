package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.dto.ApuracaoResponse;
import br.com.agrocontabil.event.NotaCanceladaEvent;
import br.com.agrocontabil.event.NotaEmitidaEvent;
import br.com.agrocontabil.event.NotaImportadaEvent;
import br.com.agrocontabil.exception.ApuracaoNaoReabrivelException;
import br.com.agrocontabil.exception.ApuracaoPeriodoFechadoException;
import br.com.agrocontabil.exception.FuncaoNaoAutorizadaException;
import br.com.agrocontabil.exception.PapelNaoAutorizadoException;
import br.com.agrocontabil.exception.ReaberturaForaPrazoException;
import br.com.agrocontabil.exception.ResourceNotFoundException;
import br.com.agrocontabil.model.ApuracaoMensal;
import br.com.agrocontabil.model.FuncaoEscritorio;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusApuracao;
import br.com.agrocontabil.repository.ApuracaoMensalRepository;
import br.com.agrocontabil.repository.CreditoSaldoAgregado;
import br.com.agrocontabil.repository.DebitoSaldoAgregado;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.security.MembroEscritorioProvider;
import br.com.agrocontabil.security.ProdutorEmContextoResolver;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.YearMonth;
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
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;

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
  @Mock private PlatformTransactionManager transactionManager;
  @Mock private ProdutorEmContextoResolver produtorEmContexto;
  @Mock private MembroEscritorioProvider membroProvider;

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

  /** Lote do fechamento nos testes: grande o bastante para caber tudo numa única leitura. */
  private static final int LOTE_PADRAO = 100;

  private ApuracaoService novoService(int loteTamanho) {
    return new ApuracaoService(
        notaFiscalRepository, apuracaoRepository, produtorRepository, produtorProvider,
        produtorEmContexto, membroProvider, auditorAware, CLOCK_FIXO, transactionManager,
        loteTamanho);
  }

  @BeforeEach
  void setUp() {
    service = novoService(LOTE_PADRAO);
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
  @DisplayName("Contador reabre apuracao FECHADA dentro de 3 meses: ABERTO, quem reabriu e o "
      + "resultado auditavel com id e produtor (#224)")
  void reabreDentroDoPrazo() {
    ApuracaoMensal apuracao = fechada("2026-06");
    UUID contadorId = UUID.randomUUID();
    when(auditorAware.getCurrentAuditor()).thenReturn(Optional.of(contadorId.toString()));
    when(apuracaoRepository.findByProdutorIdAndPeriodoForUpdate(produtor.getId(), "2026-06"))
        .thenReturn(Optional.of(apuracao));
    when(apuracaoRepository.save(any(ApuracaoMensal.class))).thenAnswer(inv -> inv.getArgument(0));

    ReaberturaApuracao resultado = service.reabrirPeloContador(produtor.getId(), "2026-06");

    assertThat(resultado.apuracao().status()).isEqualTo("ABERTO");
    assertThat(resultado.status()).isEqualTo("ABERTO");
    assertThat(resultado.recursoAuditado()).isEqualTo(apuracao.getId());
    assertThat(resultado.produtorAuditado()).isEqualTo(produtor.getId());
    InOrder ordem = inOrder(produtorEmContexto, membroProvider, apuracaoRepository);
    ordem.verify(produtorEmContexto).exigirContadorComAcesso(produtor.getId());
    ordem.verify(membroProvider).exigirFuncao(FuncaoEscritorio.ADMINISTRADOR,
        FuncaoEscritorio.CONTADOR);
    ordem.verify(apuracaoRepository).findByProdutorIdAndPeriodoForUpdate(produtor.getId(),
        "2026-06");
    verify(apuracaoRepository).save(apuracaoCaptor.capture());
    ApuracaoMensal salva = apuracaoCaptor.getValue();
    assertThat(salva.getStatus()).isEqualTo(StatusApuracao.ABERTO);
    assertThat(salva.getDataFechamento()).isNull();
    assertThat(salva.getReabertoPor()).isEqualTo(contadorId.toString());
    assertThat(salva.getDataReabertura()).isNotNull();
  }

  @Test
  @DisplayName("Reabre o 3o mes retroativo (2026-04): borda inclusiva permitida")
  void reabreTerceiroMesRetroativoPermitido() {
    ApuracaoMensal apuracao = fechada("2026-04");
    when(apuracaoRepository.findByProdutorIdAndPeriodoForUpdate(produtor.getId(), "2026-04"))
        .thenReturn(Optional.of(apuracao));
    when(apuracaoRepository.save(any(ApuracaoMensal.class))).thenAnswer(inv -> inv.getArgument(0));

    assertThat(service.reabrirPeloContador(produtor.getId(), "2026-04").status())
        .isEqualTo("ABERTO");
  }

  @Test
  @DisplayName("Reabertura do 4o mes retroativo (2026-03) fora do prazo: 422, nao persiste")
  void reaberturaQuartoMesRetroativoNegada() {
    ApuracaoMensal apuracao = fechada("2026-03");
    when(apuracaoRepository.findByProdutorIdAndPeriodoForUpdate(produtor.getId(), "2026-03"))
        .thenReturn(Optional.of(apuracao));

    assertThatThrownBy(() -> service.reabrirPeloContador(produtor.getId(), "2026-03"))
        .isInstanceOf(ReaberturaForaPrazoException.class);

    verify(apuracaoRepository, never()).save(any());
  }

  @Test
  @DisplayName("Reabertura de apuracao nao FECHADA (ABERTO) lanca 409, nao persiste")
  void reaberturaStatusInvalidoLanca409() {
    ApuracaoMensal aberta = fechada("2026-06");
    aberta.setStatus(StatusApuracao.ABERTO);
    when(apuracaoRepository.findByProdutorIdAndPeriodoForUpdate(produtor.getId(), "2026-06"))
        .thenReturn(Optional.of(aberta));

    assertThatThrownBy(() -> service.reabrirPeloContador(produtor.getId(), "2026-06"))
        .isInstanceOf(ApuracaoNaoReabrivelException.class);

    verify(apuracaoRepository, never()).save(any());
  }

  @Test
  @DisplayName("Periodo sem apuracao consolidada: 404, nao persiste")
  void reaberturaSemApuracaoRetorna404() {
    when(apuracaoRepository.findByProdutorIdAndPeriodoForUpdate(produtor.getId(), "2026-06"))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.reabrirPeloContador(produtor.getId(), "2026-06"))
        .isInstanceOf(ResourceNotFoundException.class);

    verify(apuracaoRepository, never()).save(any());
  }

  @Test
  @DisplayName("Produtor fora da carteira ativa: 404 do resolver antes de tocar a apuracao")
  void reaberturaForaDaCarteiraRetorna404() {
    doThrow(new ResourceNotFoundException("Produtor", produtor.getId()))
        .when(produtorEmContexto).exigirContadorComAcesso(produtor.getId());

    assertThatThrownBy(() -> service.reabrirPeloContador(produtor.getId(), "2026-06"))
        .isInstanceOf(ResourceNotFoundException.class);

    verifyNoInteractions(apuracaoRepository, membroProvider);
  }

  @Test
  @DisplayName("Assistente do escritorio nao reabre: 403 antes de tocar a apuracao")
  void assistenteNaoReabre() {
    when(membroProvider.exigirFuncao(FuncaoEscritorio.ADMINISTRADOR, FuncaoEscritorio.CONTADOR))
        .thenThrow(new FuncaoNaoAutorizadaException(FuncaoEscritorio.ASSISTENTE,
            List.of(FuncaoEscritorio.ADMINISTRADOR, FuncaoEscritorio.CONTADOR)));

    assertThatThrownBy(() -> service.reabrirPeloContador(produtor.getId(), "2026-06"))
        .isInstanceOf(FuncaoNaoAutorizadaException.class);

    verifyNoInteractions(apuracaoRepository);
  }

  @Test
  @DisplayName("Rota antiga POST /api/apuracao/{id}/reabrir: produtor recebe 403 e nada e lido")
  void produtorNaoReabrePelaRotaAntiga() {
    when(produtorProvider.get()).thenReturn(produtor);
    UUID id = UUID.randomUUID();

    assertThatThrownBy(() -> service.reabrir(id))
        .isInstanceOf(PapelNaoAutorizadoException.class);

    verifyNoInteractions(apuracaoRepository);
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
    when(apuracaoRepository.findByProdutorIdAndPeriodoForUpdate(produtor.getId(), "2026-07"))
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
    when(apuracaoRepository.findByProdutorIdAndPeriodoForUpdate(any(), eq("2026-01")))
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
    when(apuracaoRepository.findByProdutorIdAndPeriodoForUpdate(produtor.getId(), "2026-07"))
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
    when(apuracaoRepository.findByProdutorIdAndPeriodoForUpdate(any(), any())).thenReturn(Optional.empty());
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
    when(apuracaoRepository.findByProdutorIdAndPeriodoForUpdate(produtor.getId(), "2026-07"))
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
    when(produtorRepository.findIdsOrdenados(any())).thenReturn(List.of(produtor.getId()));
    when(notaFiscalRepository.somarDebitosPorPeriodo(any(), any(), any()))
        .thenReturn(new DebitoSaldoAgregado(new BigDecimal("100.00"), new BigDecimal("50.00"), 2L));
    when(notaFiscalRepository.somarCreditosPorPeriodo(any(), any(), any()))
        .thenReturn(new CreditoSaldoAgregado(new BigDecimal("10.00"), new BigDecimal("5.00"), 1L));
    when(apuracaoRepository.findByProdutorIdAndPeriodoForUpdate(produtor.getId(), "2026-07"))
        .thenReturn(Optional.of(fechada));
    when(apuracaoRepository.save(any(ApuracaoMensal.class))).thenAnswer(inv -> inv.getArgument(0));

    ResumoLoteApuracao resumo = service.fecharPeriodo("2026-07");

    assertThat(resumo.concluidas()).isEqualTo(1);
    assertThat(resumo.houveFalha()).isFalse();
    verify(apuracaoRepository).save(apuracaoCaptor.capture());
    assertThat(apuracaoCaptor.getValue().getStatus()).isEqualTo(StatusApuracao.FECHADO);
  }

  @Test
  @DisplayName("Refecha apuracoes ABERTAS de meses anteriores na janela RN005 (issue #57)")
  void refechaAbertasDeMesesAnterioresNaJanela() {
    ApuracaoMensal abril = aberta("2026-04");
    ApuracaoMensal maio = aberta("2026-05");
    when(apuracaoRepository.findIdsByStatusAndPeriodoEntre(
        StatusApuracao.ABERTO, "2026-04", "2026-07"))
        .thenReturn(List.of(abril.getId(), maio.getId()));
    when(apuracaoRepository.findByIdForUpdate(abril.getId())).thenReturn(Optional.of(abril));
    when(apuracaoRepository.findByIdForUpdate(maio.getId())).thenReturn(Optional.of(maio));
    when(notaFiscalRepository.somarDebitosPorPeriodo(any(), any(), any()))
        .thenReturn(new DebitoSaldoAgregado(new BigDecimal("100.00"), new BigDecimal("50.00"), 2L));
    when(notaFiscalRepository.somarCreditosPorPeriodo(any(), any(), any()))
        .thenReturn(new CreditoSaldoAgregado(new BigDecimal("10.00"), new BigDecimal("5.00"), 1L));
    when(apuracaoRepository.save(any(ApuracaoMensal.class))).thenAnswer(inv -> inv.getArgument(0));

    ResumoLoteApuracao resumo = service.refecharPeriodosReabertos();

    assertThat(resumo.concluidas()).isEqualTo(2);
    assertThat(resumo.houveFalha()).isFalse();
    verify(apuracaoRepository, times(2)).save(apuracaoCaptor.capture());
    assertThat(apuracaoCaptor.getAllValues()).allSatisfy(a -> {
      assertThat(a.getStatus()).isEqualTo(StatusApuracao.FECHADO);
      // CLOCK_FIXO = 2026-07-15T12:00:00Z; em America/Sao_Paulo (-03:00) => 2026-07-15T09:00:00-03:00.
      assertThat(a.getDataFechamento())
          .isEqualTo(OffsetDateTime.parse("2026-07-15T09:00:00-03:00"));
    });
    // Carrega cada apuracao por ID na propria transacao: nao refaz find por (produtor, periodo).
    verify(apuracaoRepository, never()).findByProdutorIdAndPeriodoForUpdate(any(), any());
    // Uma transacao por apuracao (issue #100).
    verify(transactionManager, times(2)).commit(any());
  }

  @Test
  @DisplayName("Refechamento consulta a janela RN005 [mesCorrente-3, mesCorrente) por ABERTO (issue #57)")
  void refecharConsultaJanelaRn005SemAbertasRetornaZero() {
    when(apuracaoRepository.findIdsByStatusAndPeriodoEntre(any(), any(), any()))
        .thenReturn(List.of());

    ResumoLoteApuracao resumo = service.refecharPeriodosReabertos();

    assertThat(resumo.total()).isZero();
    // Janela de refechamento = mesma janela da reabertura (RN005): [2026-04, 2026-07).
    verify(apuracaoRepository).findIdsByStatusAndPeriodoEntre(
        StatusApuracao.ABERTO, "2026-04", "2026-07");
    verifyNoInteractions(transactionManager);
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
    when(produtorRepository.findIdsOrdenados(any()))
        .thenReturn(List.of(produtor.getId(), p2.getId()));
    when(notaFiscalRepository.somarDebitosPorPeriodo(any(), any(), any()))
        .thenReturn(new DebitoSaldoAgregado(new BigDecimal("100.00"), new BigDecimal("50.00"), 2L));
    when(notaFiscalRepository.somarCreditosPorPeriodo(any(), any(), any()))
        .thenReturn(new CreditoSaldoAgregado(new BigDecimal("10.00"), new BigDecimal("5.00"), 1L));
    when(apuracaoRepository.findByProdutorIdAndPeriodoForUpdate(any(), eq("2026-07")))
        .thenReturn(Optional.empty());
    when(apuracaoRepository.save(any(ApuracaoMensal.class))).thenAnswer(inv -> inv.getArgument(0));

    ResumoLoteApuracao resumo = service.fecharPeriodo("2026-07");

    assertThat(resumo.concluidas()).isEqualTo(2);
    assertThat(resumo.falhas()).isEmpty();
    verify(apuracaoRepository, times(2)).save(apuracaoCaptor.capture());
    List<ApuracaoMensal> salvas = apuracaoCaptor.getAllValues();
    assertThat(salvas).allSatisfy(a -> {
      assertThat(a.getStatus()).isEqualTo(StatusApuracao.FECHADO);
      assertThat(a.getDataFechamento()).isNotNull();
      assertThat(a.getSaldoIbs()).isEqualByComparingTo("90.00");
    });
    // O job nao usa o produtor autenticado (roda sem contexto de seguranca).
    verifyNoInteractions(produtorProvider);
    // Nunca materializa todos os produtores (issue #100).
    verify(produtorRepository, never()).findAll();
  }

  @Test
  @DisplayName("Fechamento carimba dataFechamento a partir do Clock injetado (determinismo — issue #56)")
  void fechamentoUsaClockInjetado() {
    when(produtorRepository.findIdsOrdenados(any())).thenReturn(List.of(produtor.getId()));
    when(notaFiscalRepository.somarDebitosPorPeriodo(any(), any(), any()))
        .thenReturn(new DebitoSaldoAgregado(new BigDecimal("100.00"), new BigDecimal("50.00"), 2L));
    when(notaFiscalRepository.somarCreditosPorPeriodo(any(), any(), any()))
        .thenReturn(new CreditoSaldoAgregado(new BigDecimal("10.00"), new BigDecimal("5.00"), 1L));
    when(apuracaoRepository.findByProdutorIdAndPeriodoForUpdate(any(), eq("2026-07")))
        .thenReturn(Optional.empty());
    when(apuracaoRepository.save(any(ApuracaoMensal.class))).thenAnswer(inv -> inv.getArgument(0));

    service.fecharPeriodo("2026-07");

    verify(apuracaoRepository).save(apuracaoCaptor.capture());
    // CLOCK_FIXO = 2026-07-15T12:00:00Z; em America/Sao_Paulo (-03:00) => 2026-07-15T09:00:00-03:00.
    assertThat(apuracaoCaptor.getValue().getDataFechamento())
        .isEqualTo(OffsetDateTime.parse("2026-07-15T09:00:00-03:00"));
  }

  // -------------------------------------------------------------------------
  // Fechamento em lote (issue #100): um produtor por transacao, leitura por keyset
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("Falha de um produtor no fechamento nao aborta os demais e entra no resumo (issue #100)")
  void falhaDeUmProdutorNaoAbortaOsDemais() {
    UUID ok1 = UUID.randomUUID();
    UUID quebrado = UUID.randomUUID();
    UUID ok2 = UUID.randomUUID();
    when(produtorRepository.findIdsOrdenados(any())).thenReturn(List.of(ok1, quebrado, ok2));
    when(apuracaoRepository.findByProdutorIdAndPeriodoForUpdate(any(), eq("2026-07")))
        .thenReturn(Optional.empty());
    when(notaFiscalRepository.somarDebitosPorPeriodo(any(), any(), any()))
        .thenReturn(new DebitoSaldoAgregado(new BigDecimal("100.00"), new BigDecimal("50.00"), 2L));
    when(notaFiscalRepository.somarDebitosPorPeriodo(eq(quebrado), any(), any()))
        .thenThrow(new IllegalStateException("dado inconsistente"));
    when(notaFiscalRepository.somarCreditosPorPeriodo(any(), any(), any()))
        .thenReturn(new CreditoSaldoAgregado(BigDecimal.ZERO, BigDecimal.ZERO, 0L));
    when(apuracaoRepository.save(any(ApuracaoMensal.class))).thenAnswer(inv -> inv.getArgument(0));

    ResumoLoteApuracao resumo = service.fecharPeriodo("2026-07");

    assertThat(resumo.concluidas()).isEqualTo(2);
    assertThat(resumo.falhas()).containsExactly(quebrado);
    assertThat(resumo.total()).isEqualTo(3);
    // Os dois produtores sem problema foram gravados; o quebrado nem chegou ao save.
    verify(apuracaoRepository, times(2)).save(any(ApuracaoMensal.class));
    // Uma transacao por produtor: 2 commits e 1 rollback, nunca uma transacao de lote.
    verify(transactionManager, times(2)).commit(any());
    verify(transactionManager, times(1)).rollback(any());
  }

  @Test
  @DisplayName("Falha no commit de um produtor (ex.: unique) tambem fica isolada (issue #100)")
  void falhaNoCommitDeUmProdutorFicaIsolada() {
    UUID primeiro = UUID.randomUUID();
    UUID segundo = UUID.randomUUID();
    when(produtorRepository.findIdsOrdenados(any())).thenReturn(List.of(primeiro, segundo));
    when(apuracaoRepository.findByProdutorIdAndPeriodoForUpdate(any(), eq("2026-07")))
        .thenReturn(Optional.empty());
    when(notaFiscalRepository.somarDebitosPorPeriodo(any(), any(), any()))
        .thenReturn(new DebitoSaldoAgregado(BigDecimal.ZERO, BigDecimal.ZERO, 0L));
    when(notaFiscalRepository.somarCreditosPorPeriodo(any(), any(), any()))
        .thenReturn(new CreditoSaldoAgregado(BigDecimal.ZERO, BigDecimal.ZERO, 0L));
    when(apuracaoRepository.save(any(ApuracaoMensal.class))).thenAnswer(inv -> inv.getArgument(0));
    // O primeiro commit estoura (flush com violacao de unique); o segundo commita normalmente.
    doThrow(new DataIntegrityViolationException("uk_apuracao_produtor_periodo"))
        .doNothing()
        .when(transactionManager).commit(any());

    ResumoLoteApuracao resumo = service.fecharPeriodo("2026-07");

    assertThat(resumo.falhas()).containsExactly(primeiro);
    assertThat(resumo.concluidas()).isEqualTo(1);
  }

  @Test
  @DisplayName("Fechamento le produtores em lotes por keyset ate a pagina curta (issue #100)")
  void fechamentoLeProdutoresEmLotesPorKeyset() {
    service = novoService(2);
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    UUID c = UUID.randomUUID();
    when(produtorRepository.findIdsOrdenados(any())).thenReturn(List.of(a, b));
    when(produtorRepository.findIdsOrdenadosApos(eq(b), any())).thenReturn(List.of(c));
    when(apuracaoRepository.findByProdutorIdAndPeriodoForUpdate(any(), eq("2026-07")))
        .thenReturn(Optional.empty());
    when(notaFiscalRepository.somarDebitosPorPeriodo(any(), any(), any()))
        .thenReturn(new DebitoSaldoAgregado(BigDecimal.ZERO, BigDecimal.ZERO, 0L));
    when(notaFiscalRepository.somarCreditosPorPeriodo(any(), any(), any()))
        .thenReturn(new CreditoSaldoAgregado(BigDecimal.ZERO, BigDecimal.ZERO, 0L));
    when(apuracaoRepository.save(any(ApuracaoMensal.class))).thenAnswer(inv -> inv.getArgument(0));

    ResumoLoteApuracao resumo = service.fecharPeriodo("2026-07");

    assertThat(resumo.concluidas()).isEqualTo(3);
    ArgumentCaptor<Pageable> loteCaptor = ArgumentCaptor.forClass(Pageable.class);
    verify(produtorRepository).findIdsOrdenados(loteCaptor.capture());
    assertThat(loteCaptor.getValue()).isEqualTo(PageRequest.ofSize(2));
    // A 2a leitura parte do ultimo ID do 1o lote; a 3a nao acontece (2o lote veio curto).
    verify(produtorRepository).findIdsOrdenadosApos(eq(b), eq(PageRequest.ofSize(2)));
    verify(produtorRepository, never()).findIdsOrdenadosApos(eq(c), any());
  }

  @Test
  @DisplayName("Lote cheio seguido de lote vazio encerra a varredura (issue #100)")
  void loteCheioSeguidoDeLoteVazioEncerra() {
    service = novoService(1);
    when(produtorRepository.findIdsOrdenados(any())).thenReturn(List.of(produtor.getId()));
    when(produtorRepository.findIdsOrdenadosApos(eq(produtor.getId()), any()))
        .thenReturn(List.of());
    when(apuracaoRepository.findByProdutorIdAndPeriodoForUpdate(any(), eq("2026-07")))
        .thenReturn(Optional.empty());
    when(notaFiscalRepository.somarDebitosPorPeriodo(any(), any(), any()))
        .thenReturn(new DebitoSaldoAgregado(BigDecimal.ZERO, BigDecimal.ZERO, 0L));
    when(notaFiscalRepository.somarCreditosPorPeriodo(any(), any(), any()))
        .thenReturn(new CreditoSaldoAgregado(BigDecimal.ZERO, BigDecimal.ZERO, 0L));
    when(apuracaoRepository.save(any(ApuracaoMensal.class))).thenAnswer(inv -> inv.getArgument(0));

    ResumoLoteApuracao resumo = service.fecharPeriodo("2026-07");

    assertThat(resumo.concluidas()).isEqualTo(1);
  }

  @Test
  @DisplayName("Sem produtores o fechamento devolve resumo vazio sem abrir transacao (issue #100)")
  void semProdutoresNaoAbreTransacao() {
    when(produtorRepository.findIdsOrdenados(any())).thenReturn(List.of());

    ResumoLoteApuracao resumo = service.fecharPeriodo("2026-07");

    assertThat(resumo.total()).isZero();
    verifyNoInteractions(transactionManager, notaFiscalRepository);
  }

  @Test
  @DisplayName("Falha ao refechar uma apuracao nao aborta as demais (issue #100)")
  void falhaAoRefecharUmaNaoAbortaAsDemais() {
    ApuracaoMensal abril = aberta("2026-04");
    ApuracaoMensal maio = aberta("2026-05");
    when(apuracaoRepository.findIdsByStatusAndPeriodoEntre(
        StatusApuracao.ABERTO, "2026-04", "2026-07"))
        .thenReturn(List.of(abril.getId(), maio.getId()));
    when(apuracaoRepository.findByIdForUpdate(abril.getId()))
        .thenThrow(new IllegalStateException("falha ao carregar"));
    when(apuracaoRepository.findByIdForUpdate(maio.getId())).thenReturn(Optional.of(maio));
    when(notaFiscalRepository.somarDebitosPorPeriodo(any(), any(), any()))
        .thenReturn(new DebitoSaldoAgregado(BigDecimal.ZERO, BigDecimal.ZERO, 0L));
    when(notaFiscalRepository.somarCreditosPorPeriodo(any(), any(), any()))
        .thenReturn(new CreditoSaldoAgregado(BigDecimal.ZERO, BigDecimal.ZERO, 0L));
    when(apuracaoRepository.save(any(ApuracaoMensal.class))).thenAnswer(inv -> inv.getArgument(0));

    ResumoLoteApuracao resumo = service.refecharPeriodosReabertos();

    assertThat(resumo.falhas()).containsExactly(abril.getId());
    assertThat(resumo.concluidas()).isEqualTo(1);
    assertThat(maio.getStatus()).isEqualTo(StatusApuracao.FECHADO);
  }

  @Test
  @DisplayName("Apuracao removida entre a leitura dos IDs e a transacao dela e ignorada (issue #100)")
  void apuracaoRemovidaNoMeioEIgnorada() {
    UUID sumiu = UUID.randomUUID();
    when(apuracaoRepository.findIdsByStatusAndPeriodoEntre(any(), any(), any()))
        .thenReturn(List.of(sumiu));
    when(apuracaoRepository.findByIdForUpdate(sumiu)).thenReturn(Optional.empty());

    ResumoLoteApuracao resumo = service.refecharPeriodosReabertos();

    assertThat(resumo.total()).isZero();
    verify(apuracaoRepository, never()).save(any());
  }

  @Test
  @DisplayName("Refechamento ignora apuracao que ja nao esta ABERTA sob lock (issue #100)")
  void refechamentoIgnoraApuracaoQueJaFoiFechada() {
    ApuracaoMensal jaFechada = fechada("2026-05");
    OffsetDateTime fechamentoOriginal = jaFechada.getDataFechamento();
    when(apuracaoRepository.findIdsByStatusAndPeriodoEntre(any(), any(), any()))
        .thenReturn(List.of(jaFechada.getId()));
    when(apuracaoRepository.findByIdForUpdate(jaFechada.getId()))
        .thenReturn(Optional.of(jaFechada));

    ResumoLoteApuracao resumo = service.refecharPeriodosReabertos();

    // Foi fechada por outra via entre a leitura dos IDs e a transacao dela: nao reconsolida,
    // nao conta como refechada e preserva a dataFechamento original.
    assertThat(resumo.total()).isZero();
    assertThat(jaFechada.getDataFechamento()).isEqualTo(fechamentoOriginal);
    verify(apuracaoRepository, never()).save(any());
    verifyNoInteractions(notaFiscalRepository);
  }

  @Test
  @DisplayName("Fechamento interrompido (shutdown) para entre produtores sem abrir transacao (#100)")
  void fechamentoInterrompidoParaEntreProdutores() {
    when(produtorRepository.findIdsOrdenados(any())).thenReturn(List.of(produtor.getId()));

    Thread.currentThread().interrupt();
    try {
      ResumoLoteApuracao resumo = service.fecharPeriodo("2026-07");

      assertThat(resumo.total()).isZero();
      verifyNoInteractions(transactionManager, notaFiscalRepository);
    } finally {
      // Limpa o flag para nao vazar a interrupcao para os proximos testes da mesma thread.
      Thread.interrupted();
    }
  }

  @Test
  @DisplayName("Refechamento interrompido (shutdown) para sem abrir transacao (issue #100)")
  void refechamentoInterrompidoPara() {
    when(apuracaoRepository.findIdsByStatusAndPeriodoEntre(any(), any(), any()))
        .thenReturn(List.of(UUID.randomUUID()));

    Thread.currentThread().interrupt();
    try {
      ResumoLoteApuracao resumo = service.refecharPeriodosReabertos();

      assertThat(resumo.total()).isZero();
      verifyNoInteractions(transactionManager);
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  @DisplayName("Tamanho de lote menor que 1 e rejeitado na construcao (issue #100)")
  void loteInvalidoRejeitado() {
    assertThatThrownBy(() -> novoService(0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("lote-tamanho");
  }

  // -------------------------------------------------------------------------
  // Listeners de evento de dominio (issue Agrocontabil#64 — recalculo ao cancelar/emitir/importar)
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("Evento de nota cancelada recalcula apuracao ABERTO ja consolidada (issue #64)")
  void eventoNotaCanceladaRecalculaApuracaoAberta() {
    ApuracaoMensal existente = aberta("2026-07");
    existente.setDebitoIbs(new BigDecimal("100.00"));
    when(apuracaoRepository.findByProdutorIdAndPeriodoForUpdate(produtor.getId(), "2026-07"))
        .thenReturn(Optional.of(existente));
    when(notaFiscalRepository.somarDebitosPorPeriodo(any(), any(), any()))
        .thenReturn(new DebitoSaldoAgregado(new BigDecimal("60.00"), new BigDecimal("30.00"), 1L));
    when(notaFiscalRepository.somarCreditosPorPeriodo(any(), any(), any()))
        .thenReturn(new CreditoSaldoAgregado(BigDecimal.ZERO, BigDecimal.ZERO, 0L));
    when(apuracaoRepository.save(any(ApuracaoMensal.class))).thenAnswer(inv -> inv.getArgument(0));

    service.aoNotaCancelada(new NotaCanceladaEvent(produtor.getId(), YearMonth.of(2026, 7)));

    verify(apuracaoRepository).save(apuracaoCaptor.capture());
    assertThat(apuracaoCaptor.getValue().getDebitoIbs()).isEqualByComparingTo("60.00");
    assertThat(apuracaoCaptor.getValue().getStatus()).isEqualTo(StatusApuracao.ABERTO);
  }

  @Test
  @DisplayName("Evento de nota emitida recalcula apuracao ABERTO ja consolidada (issue #64)")
  void eventoNotaEmitidaRecalculaApuracaoAberta() {
    ApuracaoMensal existente = aberta("2026-07");
    when(apuracaoRepository.findByProdutorIdAndPeriodoForUpdate(produtor.getId(), "2026-07"))
        .thenReturn(Optional.of(existente));
    when(notaFiscalRepository.somarDebitosPorPeriodo(any(), any(), any()))
        .thenReturn(new DebitoSaldoAgregado(new BigDecimal("60.00"), new BigDecimal("30.00"), 1L));
    when(notaFiscalRepository.somarCreditosPorPeriodo(any(), any(), any()))
        .thenReturn(new CreditoSaldoAgregado(BigDecimal.ZERO, BigDecimal.ZERO, 0L));
    when(apuracaoRepository.save(any(ApuracaoMensal.class))).thenAnswer(inv -> inv.getArgument(0));

    service.aoNotaEmitida(new NotaEmitidaEvent(produtor.getId(), YearMonth.of(2026, 7)));

    verify(apuracaoRepository).save(apuracaoCaptor.capture());
    assertThat(apuracaoCaptor.getValue().getDebitoIbs()).isEqualByComparingTo("60.00");
  }

  @Test
  @DisplayName("Evento de nota importada recalcula apuracao ABERTO ja consolidada (issue #64)")
  void eventoNotaImportadaRecalculaApuracaoAberta() {
    ApuracaoMensal existente = aberta("2026-07");
    when(apuracaoRepository.findByProdutorIdAndPeriodoForUpdate(produtor.getId(), "2026-07"))
        .thenReturn(Optional.of(existente));
    when(notaFiscalRepository.somarDebitosPorPeriodo(any(), any(), any()))
        .thenReturn(new DebitoSaldoAgregado(BigDecimal.ZERO, BigDecimal.ZERO, 0L));
    when(notaFiscalRepository.somarCreditosPorPeriodo(any(), any(), any()))
        .thenReturn(new CreditoSaldoAgregado(new BigDecimal("20.00"), new BigDecimal("10.00"), 1L));
    when(apuracaoRepository.save(any(ApuracaoMensal.class))).thenAnswer(inv -> inv.getArgument(0));

    service.aoNotaImportada(new NotaImportadaEvent(produtor.getId(), YearMonth.of(2026, 7)));

    verify(apuracaoRepository).save(apuracaoCaptor.capture());
    assertThat(apuracaoCaptor.getValue().getCreditoIbs()).isEqualByComparingTo("20.00");
  }

  @Test
  @DisplayName("Evento sobre periodo FECHADO nao recalcula: preserva o saldo consolidado (issue #64)")
  void eventoSobrePeriodoFechadoNaoRecalcula() {
    ApuracaoMensal fechada = fechada("2026-07");
    when(apuracaoRepository.findByProdutorIdAndPeriodoForUpdate(produtor.getId(), "2026-07"))
        .thenReturn(Optional.of(fechada));

    service.aoNotaCancelada(new NotaCanceladaEvent(produtor.getId(), YearMonth.of(2026, 7)));

    verify(apuracaoRepository, never()).save(any());
    verifyNoInteractions(notaFiscalRepository);
  }

  @Test
  @DisplayName("Evento sobre periodo nao consolidado e no-op: projecao ao vivo ja reflete (issue #64)")
  void eventoSobrePeriodoNaoConsolidadoNaoOp() {
    when(apuracaoRepository.findByProdutorIdAndPeriodoForUpdate(produtor.getId(), "2026-07"))
        .thenReturn(Optional.empty());

    service.aoNotaCancelada(new NotaCanceladaEvent(produtor.getId(), YearMonth.of(2026, 7)));

    verify(apuracaoRepository, never()).save(any());
    verifyNoInteractions(notaFiscalRepository);
  }
}
