package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.dto.CreditoSaldoResponse;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.repository.CreditoSaldoAgregado;
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
 * Testes unitários do {@link CreditoService} (issue Agrocontabil#30 — RN003, saldo de créditos).
 *
 * <p>Isola a agregação e o parsing de período com mocks do repositório e do provedor de
 * produtor autenticado. Verifica o recorte por produtor (anti-IDOR) e a janela temporal do
 * período no fuso {@code America/Sao_Paulo}.
 */
@ExtendWith(MockitoExtension.class)
class CreditoServiceTest {

  @Mock private NotaFiscalRepository notaFiscalRepository;
  @Mock private AuthenticatedProdutorProvider produtorProvider;

  @InjectMocks private CreditoService service;

  @Captor private ArgumentCaptor<OffsetDateTime> inicioCaptor;
  @Captor private ArgumentCaptor<OffsetDateTime> fimCaptor;

  private Produtor produtor;

  @BeforeEach
  void setUp() {
    produtor = Produtor.builder().id(UUID.randomUUID()).nomeCompleto("Produtor").build();
  }

  @Test
  @DisplayName("Saldo agrega credito IBS/CBS do produtor autenticado no periodo")
  void saldoAgregaCreditos() {
    when(produtorProvider.get()).thenReturn(produtor);
    when(notaFiscalRepository.somarCreditosPorPeriodo(eq(produtor.getId()), inicioCaptor.capture(),
        fimCaptor.capture()))
        .thenReturn(new CreditoSaldoAgregado(new BigDecimal("12.50"), new BigDecimal("112.50"), 3L));

    CreditoSaldoResponse resposta = service.consultarSaldo("2026-07");

    assertThat(resposta.periodo()).isEqualTo("2026-07");
    assertThat(resposta.creditoIbs()).isEqualByComparingTo("12.50");
    assertThat(resposta.creditoCbs()).isEqualByComparingTo("112.50");
    assertThat(resposta.creditoTotal()).isEqualByComparingTo("125.00");
    assertThat(resposta.quantidadeNotas()).isEqualTo(3L);

    // Janela [inicio, fim) do mes em America/Sao_Paulo (-03:00).
    assertThat(inicioCaptor.getValue()).isEqualTo(OffsetDateTime.parse("2026-07-01T00:00:00-03:00"));
    assertThat(fimCaptor.getValue()).isEqualTo(OffsetDateTime.parse("2026-08-01T00:00:00-03:00"));
  }

  @Test
  @DisplayName("Periodo sem notas retorna saldo zerado (nulls do SUM viram 0.00)")
  void periodoSemNotasRetornaZero() {
    when(produtorProvider.get()).thenReturn(produtor);
    when(notaFiscalRepository.somarCreditosPorPeriodo(eq(produtor.getId()), inicioCaptor.capture(),
        fimCaptor.capture()))
        .thenReturn(new CreditoSaldoAgregado(null, null, 0L));

    CreditoSaldoResponse resposta = service.consultarSaldo("2026-01");

    assertThat(resposta.creditoIbs()).isEqualByComparingTo("0.00");
    assertThat(resposta.creditoCbs()).isEqualByComparingTo("0.00");
    assertThat(resposta.creditoTotal()).isEqualByComparingTo("0.00");
    assertThat(resposta.quantidadeNotas()).isZero();
  }

  @Test
  @DisplayName("Consulta usa o ID do produtor autenticado (anti-IDOR: recorte no repositorio)")
  void consultaRecortaPorProdutorAutenticado() {
    ArgumentCaptor<UUID> produtorIdCaptor = ArgumentCaptor.forClass(UUID.class);
    when(produtorProvider.get()).thenReturn(produtor);
    when(notaFiscalRepository.somarCreditosPorPeriodo(any(), any(), any()))
        .thenReturn(new CreditoSaldoAgregado(BigDecimal.ZERO, BigDecimal.ZERO, 0L));

    service.consultarSaldo("2026-07");

    verify(notaFiscalRepository)
        .somarCreditosPorPeriodo(produtorIdCaptor.capture(), any(), any());
    assertThat(produtorIdCaptor.getValue()).isEqualTo(produtor.getId());
  }

  @Test
  @DisplayName("Periodo em formato invalido lanca IllegalArgumentException (400), sem tocar no banco")
  void periodoInvalidoLancaExcecao() {
    assertThatThrownBy(() -> service.consultarSaldo("2026-13"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.consultarSaldo("julho/2026"))
        .isInstanceOf(IllegalArgumentException.class);

    verifyNoInteractions(notaFiscalRepository);
  }
}
