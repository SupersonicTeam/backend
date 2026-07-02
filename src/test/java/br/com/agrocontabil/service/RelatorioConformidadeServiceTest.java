package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.dto.RelatorioConformidadeResponse;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.repository.CreditoSaldoAgregado;
import br.com.agrocontabil.repository.DebitoSaldoAgregado;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Testes unitários do {@link RelatorioConformidadeService} (issue Agrocontabil#32 — UC004).
 *
 * <p>Isola a consolidação de débitos (saídas autorizadas) × créditos (entradas) e o cálculo do
 * saldo a pagar, com mocks do repositório e do provedor de produtor autenticado.
 */
@ExtendWith(MockitoExtension.class)
class RelatorioConformidadeServiceTest {

  @Mock private NotaFiscalRepository notaFiscalRepository;
  @Mock private AuthenticatedProdutorProvider produtorProvider;

  @InjectMocks private RelatorioConformidadeService service;

  @Captor private ArgumentCaptor<OffsetDateTime> inicioCaptor;
  @Captor private ArgumentCaptor<OffsetDateTime> fimCaptor;

  private Produtor produtor;

  @BeforeEach
  void setUp() {
    produtor = Produtor.builder().id(UUID.randomUUID()).nomeCompleto("Produtor").build();
  }

  @Test
  @DisplayName("Consolida debitos das saidas menos creditos das entradas = saldo a pagar")
  void consolidaDebitosMenosCreditos() {
    when(produtorProvider.get()).thenReturn(produtor);
    when(notaFiscalRepository.somarDebitosPorPeriodo(any(), any(), any()))
        .thenReturn(new DebitoSaldoAgregado(new BigDecimal("10.00"), new BigDecimal("90.00"), 5L));
    when(notaFiscalRepository.somarCreditosPorPeriodo(any(), any(), any()))
        .thenReturn(new CreditoSaldoAgregado(new BigDecimal("4.00"), new BigDecimal("36.00"), 2L));

    RelatorioConformidadeResponse r = service.gerar("2026-07");

    assertThat(r.periodo()).isEqualTo("2026-07");
    assertThat(r.debitoIbs()).isEqualByComparingTo("10.00");
    assertThat(r.debitoCbs()).isEqualByComparingTo("90.00");
    assertThat(r.debitoTotal()).isEqualByComparingTo("100.00");
    assertThat(r.quantidadeSaidas()).isEqualTo(5L);
    assertThat(r.creditoIbs()).isEqualByComparingTo("4.00");
    assertThat(r.creditoCbs()).isEqualByComparingTo("36.00");
    assertThat(r.creditoTotal()).isEqualByComparingTo("40.00");
    assertThat(r.quantidadeEntradas()).isEqualTo(2L);
    // Saldo por tributo e total.
    assertThat(r.saldoIbs()).isEqualByComparingTo("6.00");
    assertThat(r.saldoCbs()).isEqualByComparingTo("54.00");
    assertThat(r.saldoAPagar()).isEqualByComparingTo("60.00");
  }

  @Test
  @DisplayName("Credito maior que debito gera saldo a pagar negativo (saldo credor)")
  void saldoCredorQuandoCreditoMaior() {
    when(produtorProvider.get()).thenReturn(produtor);
    when(notaFiscalRepository.somarDebitosPorPeriodo(any(), any(), any()))
        .thenReturn(new DebitoSaldoAgregado(new BigDecimal("1.00"), new BigDecimal("9.00"), 1L));
    when(notaFiscalRepository.somarCreditosPorPeriodo(any(), any(), any()))
        .thenReturn(new CreditoSaldoAgregado(new BigDecimal("5.00"), new BigDecimal("45.00"), 3L));

    RelatorioConformidadeResponse r = service.gerar("2026-07");

    assertThat(r.saldoAPagar()).isEqualByComparingTo("-40.00");
  }

  @Test
  @DisplayName("Periodo sem notas retorna tudo zerado (nulls do SUM viram 0.00)")
  void periodoVazioZera() {
    when(produtorProvider.get()).thenReturn(produtor);
    when(notaFiscalRepository.somarDebitosPorPeriodo(any(), any(), any()))
        .thenReturn(new DebitoSaldoAgregado(null, null, 0L));
    when(notaFiscalRepository.somarCreditosPorPeriodo(any(), any(), any()))
        .thenReturn(new CreditoSaldoAgregado(null, null, 0L));

    RelatorioConformidadeResponse r = service.gerar("2026-02");

    assertThat(r.debitoTotal()).isEqualByComparingTo("0.00");
    assertThat(r.creditoTotal()).isEqualByComparingTo("0.00");
    assertThat(r.saldoAPagar()).isEqualByComparingTo("0.00");
  }

  @Test
  @DisplayName("Recorte por produtor autenticado e janela do mes em America/Sao_Paulo (anti-IDOR)")
  void recorteProdutorEJanela() {
    ArgumentCaptor<UUID> idDebito = ArgumentCaptor.forClass(UUID.class);
    ArgumentCaptor<UUID> idCredito = ArgumentCaptor.forClass(UUID.class);
    when(produtorProvider.get()).thenReturn(produtor);
    when(notaFiscalRepository.somarDebitosPorPeriodo(any(), inicioCaptor.capture(),
        fimCaptor.capture()))
        .thenReturn(new DebitoSaldoAgregado(BigDecimal.ZERO, BigDecimal.ZERO, 0L));
    when(notaFiscalRepository.somarCreditosPorPeriodo(any(), any(), any()))
        .thenReturn(new CreditoSaldoAgregado(BigDecimal.ZERO, BigDecimal.ZERO, 0L));

    service.gerar("2026-07");

    verify(notaFiscalRepository).somarDebitosPorPeriodo(idDebito.capture(), any(), any());
    verify(notaFiscalRepository).somarCreditosPorPeriodo(idCredito.capture(), any(), any());
    assertThat(idDebito.getValue()).isEqualTo(produtor.getId());
    assertThat(idCredito.getValue()).isEqualTo(produtor.getId());
    assertThat(inicioCaptor.getValue()).isEqualTo(OffsetDateTime.parse("2026-07-01T00:00:00-03:00"));
    assertThat(fimCaptor.getValue()).isEqualTo(OffsetDateTime.parse("2026-08-01T00:00:00-03:00"));
  }

  @Test
  @DisplayName("Periodo invalido lanca IllegalArgumentException (400) sem tocar no banco")
  void periodoInvalido() {
    assertThatThrownBy(() -> service.gerar("2026-13"))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(notaFiscalRepository);
  }
}
