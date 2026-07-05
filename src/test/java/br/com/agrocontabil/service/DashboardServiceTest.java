package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.dto.ApuracaoResponse;
import br.com.agrocontabil.dto.DashboardResponse;
import br.com.agrocontabil.dto.DashboardResponse.NotaPorMes;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.OrigemNotaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.model.TipoProdutor;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import br.com.agrocontabil.repository.NotasPorMesAgregado;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
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
 * Testes unitários do {@link DashboardService} (issue Agrocontabil#65 — Sprint 5).
 *
 * <p>Isola a composição do dashboard com mocks das fontes já existentes: os KPIs vêm do
 * {@link ApuracaoService} (sem recomputar), o volume mensal e as últimas notas do
 * {@link NotaFiscalRepository}, e o produtor do {@link AuthenticatedProdutorProvider}. Verifica o
 * reúso da apuração, o zero-fill dos 6 meses, o recorte da janela ({@code America/Sao_Paulo}), o
 * default do mês corrente e a ausência de CPF/CNPJ no payload.
 */
@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

  @Mock private ApuracaoService apuracaoService;
  @Mock private NotaFiscalRepository notaFiscalRepository;
  @Mock private AuthenticatedProdutorProvider produtorProvider;

  @Captor private ArgumentCaptor<OffsetDateTime> inicioCaptor;
  @Captor private ArgumentCaptor<OffsetDateTime> fimCaptor;

  private DashboardService service;
  private Produtor produtor;

  /** Relógio fixo em 2026-07-15 (America/Sao_Paulo) para tornar determinísticos mês e janela. */
  private static final Clock CLOCK_FIXO = Clock.fixed(
      Instant.parse("2026-07-15T12:00:00Z"), ZoneId.of("America/Sao_Paulo"));

  private ApuracaoResponse apuracaoJulho() {
    return new ApuracaoResponse(
        "2026-07", "ABERTO",
        new BigDecimal("150.00"), new BigDecimal("1350.00"),
        new BigDecimal("30.00"), new BigDecimal("270.00"),
        new BigDecimal("120.00"), new BigDecimal("1080.00"), 8L, null);
  }

  @BeforeEach
  void setUp() {
    service = new DashboardService(
        apuracaoService, notaFiscalRepository, produtorProvider, CLOCK_FIXO);
    produtor = Produtor.builder()
        .id(UUID.randomUUID())
        .nomeCompleto("Fazenda Boa Vista")
        .cpfCnpj("12345678909")
        .tipoProdutor(TipoProdutor.PESSOA_FISICA)
        .estado("PR")
        .build();
  }

  /** Stub do produtor autenticado; fora do {@code setUp} para respeitar strict stubs no
   *  teste de early return (período inválido nunca alcança o provider). */
  private void produtorAutenticado() {
    when(produtorProvider.get()).thenReturn(produtor);
  }

  @Test
  @DisplayName("KPIs e distribuicao vem da apuracao do periodo, sem recomputar somas")
  void kpisVemDaApuracao() {
    produtorAutenticado();
    when(apuracaoService.consultar("2026-07")).thenReturn(apuracaoJulho());

    DashboardResponse dash = service.montar("2026-07");

    assertThat(dash.periodo()).isEqualTo("2026-07");
    assertThat(dash.kpis().debitoIbs()).isEqualByComparingTo("150.00");
    assertThat(dash.kpis().creditoCbs()).isEqualByComparingTo("270.00");
    assertThat(dash.kpis().saldoIbs()).isEqualByComparingTo("120.00");
    assertThat(dash.kpis().totalNotas()).isEqualTo(8L);
    // Distribuicao = debito IBS x CBS (apenas IBS/CBS modelados)
    assertThat(dash.distribuicaoImpostos().ibs()).isEqualByComparingTo("150.00");
    assertThat(dash.distribuicaoImpostos().cbs()).isEqualByComparingTo("1350.00");
  }

  @Test
  @DisplayName("Resumo do produtor expoe nome/tipo/UF, nunca o CPF/CNPJ")
  void resumoProdutorSemDocumento() {
    produtorAutenticado();
    when(apuracaoService.consultar("2026-07")).thenReturn(apuracaoJulho());

    DashboardResponse dash = service.montar("2026-07");

    assertThat(dash.produtor().nome()).isEqualTo("Fazenda Boa Vista");
    assertThat(dash.produtor().tipoProdutor()).isEqualTo("PESSOA_FISICA");
    assertThat(dash.produtor().uf()).isEqualTo("PR");
    // O record ProdutorResumo nao possui campo de documento; garante que o CPF nao vaza.
    assertThat(dash.produtor().toString()).doesNotContain("12345678909");
  }

  @Test
  @DisplayName("Periodo ausente usa o mes corrente do Clock (2026-07)")
  void periodoAusenteUsaMesCorrente() {
    produtorAutenticado();
    when(apuracaoService.consultar("2026-07")).thenReturn(apuracaoJulho());

    DashboardResponse dash = service.montar(null);

    assertThat(dash.periodo()).isEqualTo("2026-07");
    verify(apuracaoService).consultar("2026-07");
  }

  @Test
  @DisplayName("Serie mensal cobre 6 meses (fev..jul), com zero-fill dos meses sem notas")
  void serieMensalSeisMesesComZeroFill() {
    produtorAutenticado();
    when(apuracaoService.consultar("2026-07")).thenReturn(apuracaoJulho());
    // Notas so em maio (2 saidas) e julho (3 saidas + 1 entrada); demais meses zerados.
    when(notaFiscalRepository.contarNotasPorMesEOrigem(
            eq(produtor.getId()), any(), any()))
        .thenReturn(List.of(
            new NotasPorMesAgregado(2026, 5, OrigemNotaFiscal.PROPRIA, 2L),
            new NotasPorMesAgregado(2026, 7, OrigemNotaFiscal.PROPRIA, 3L),
            new NotasPorMesAgregado(2026, 7, OrigemNotaFiscal.IMPORTADA, 1L)));

    DashboardResponse dash = service.montar("2026-07");

    List<NotaPorMes> serie = dash.notasPorMes();
    assertThat(serie).hasSize(6);
    assertThat(serie).extracting(NotaPorMes::periodo)
        .containsExactly("2026-02", "2026-03", "2026-04", "2026-05", "2026-06", "2026-07");
    assertThat(serie.get(0)).extracting(NotaPorMes::entradas, NotaPorMes::saidas)
        .containsExactly(0L, 0L); // fevereiro vazio
    assertThat(serie.get(3)).extracting(NotaPorMes::entradas, NotaPorMes::saidas)
        .containsExactly(0L, 2L); // maio: 2 saidas
    assertThat(serie.get(5)).extracting(NotaPorMes::entradas, NotaPorMes::saidas)
        .containsExactly(1L, 3L); // julho: 1 entrada, 3 saidas
  }

  @Test
  @DisplayName("Janela da serie usa [inicio do 6o mes anterior, inicio do mes seguinte) no fuso BR")
  void janelaDaSerieNoFusoBrasilia() {
    produtorAutenticado();
    when(apuracaoService.consultar("2026-07")).thenReturn(apuracaoJulho());
    when(notaFiscalRepository.contarNotasPorMesEOrigem(any(), any(), any()))
        .thenReturn(List.of());

    service.montar("2026-07");

    verify(notaFiscalRepository)
        .contarNotasPorMesEOrigem(eq(produtor.getId()), inicioCaptor.capture(), fimCaptor.capture());
    assertThat(inicioCaptor.getValue())
        .isEqualTo(OffsetDateTime.parse("2026-02-01T00:00-03:00"));
    assertThat(fimCaptor.getValue())
        .isEqualTo(OffsetDateTime.parse("2026-08-01T00:00-03:00"));
  }

  @Test
  @DisplayName("Ultimas notas resolvem a contraparte pela origem (destinatario/emitente)")
  void ultimasNotasResolvemContraparte() {
    produtorAutenticado();
    when(apuracaoService.consultar("2026-07")).thenReturn(apuracaoJulho());
    NotaFiscal saida = NotaFiscal.builder()
        .id(UUID.randomUUID()).numero(1024L).origem(OrigemNotaFiscal.PROPRIA)
        .destinatarioNome("Cooperativa Agro Ltda").destinatarioCpfCnpj("11222333000181")
        .dataEmissao(OffsetDateTime.parse("2026-07-10T09:00-03:00"))
        .valorTotalNota(new BigDecimal("12500.00")).status(StatusNotaFiscal.AUTORIZADA)
        .build();
    NotaFiscal entrada = NotaFiscal.builder()
        .id(UUID.randomUUID()).numero(55L).origem(OrigemNotaFiscal.IMPORTADA)
        .emitenteNome("Insumos do Campo SA").destinatarioNome("Fazenda Boa Vista")
        .destinatarioCpfCnpj("12345678909")
        .dataEmissao(OffsetDateTime.parse("2026-07-08T09:00-03:00"))
        .valorTotalNota(new BigDecimal("3200.00")).status(StatusNotaFiscal.AUTORIZADA)
        .build();
    when(notaFiscalRepository.findTop5ByProdutorIdOrderByDataEmissaoDesc(produtor.getId()))
        .thenReturn(List.of(saida, entrada));

    DashboardResponse dash = service.montar("2026-07");

    assertThat(dash.ultimasNotas()).hasSize(2);
    assertThat(dash.ultimasNotas().get(0).contraparte()).isEqualTo("Cooperativa Agro Ltda");
    assertThat(dash.ultimasNotas().get(0).origem()).isEqualTo("PROPRIA");
    assertThat(dash.ultimasNotas().get(1).contraparte()).isEqualTo("Insumos do Campo SA");
    assertThat(dash.ultimasNotas().get(1).origem()).isEqualTo("IMPORTADA");
  }

  @Test
  @DisplayName("Periodo em formato invalido lanca IllegalArgumentException (400 no controller)")
  void periodoInvalidoLancaExcecao() {
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.montar("2026/07"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
