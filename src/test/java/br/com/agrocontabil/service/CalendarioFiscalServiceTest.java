package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.dto.VencimentoFiscalResponse;
import br.com.agrocontabil.exception.JanelaCalendarioInvalidaException;
import br.com.agrocontabil.exception.ResourceNotFoundException;
import br.com.agrocontabil.model.GuiaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusGuiaFiscal;
import br.com.agrocontabil.model.TipoGuiaFiscal;
import br.com.agrocontabil.repository.GuiaFiscalRepository;
import br.com.agrocontabil.security.ProdutorEmContextoResolver;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Testes unitários do {@link CalendarioFiscalService} (issues #74 e #103).
 *
 * <p>Relógio fixo em 2026-09-25 ({@code America/Sao_Paulo}): a janela padrão é então
 * {@code [2025-09-01, 2027-09-30]} — do 1º dia de 12 meses atrás ao último dia de 12 meses à
 * frente.
 */
@ExtendWith(MockitoExtension.class)
class CalendarioFiscalServiceTest {

  private static final Clock CLOCK_FIXO = Clock.fixed(
      Instant.parse("2026-09-25T15:00:00Z"), ZoneId.of("America/Sao_Paulo"));
  private static final LocalDate INICIO_PADRAO = LocalDate.of(2025, 9, 1);
  private static final LocalDate FIM_PADRAO = LocalDate.of(2027, 9, 30);

  @Mock private GuiaFiscalRepository guiaFiscalRepository;
  @Mock private ProdutorEmContextoResolver produtorEmContexto;

  private CalendarioFiscalService service;
  private Produtor produtor;

  @BeforeEach
  void setUp() {
    service = new CalendarioFiscalService(guiaFiscalRepository, produtorEmContexto, CLOCK_FIXO);
    produtor = Produtor.builder().id(UUID.randomUUID()).nomeCompleto("Fazenda Boa Vista").build();
  }

  @Test
  @DisplayName("listarVencimentos() mapeia guia PAGA para status 'pago' e tipo 'imposto'")
  void mapeiaGuiaPagaParaVencimentoPago() {
    when(guiaFiscalRepository.findVencimentosNaJanela(
        produtor.getId(), INICIO_PADRAO, FIM_PADRAO))
        .thenReturn(List.of(guia(TipoGuiaFiscal.ICMS, StatusGuiaFiscal.PAGA, "1250.00")));

    List<VencimentoFiscalResponse> vencimentos =
        service.listarVencimentos(produtor.getId(), null, null);

    assertThat(vencimentos).hasSize(1);
    VencimentoFiscalResponse v = vencimentos.get(0);
    assertThat(v.tipo()).isEqualTo("imposto");
    assertThat(v.status()).isEqualTo("pago");
    assertThat(v.evento()).isEqualTo("Guia ICMS");
    assertThat(v.valor()).isEqualByComparingTo("1250.00");
  }

  @Test
  @DisplayName("listarVencimentos() mapeia guia VENCIDA para status 'vencido'")
  void mapeiaGuiaVencidaParaVencimentoVencido() {
    when(guiaFiscalRepository.findVencimentosNaJanela(
        produtor.getId(), INICIO_PADRAO, FIM_PADRAO))
        .thenReturn(List.of(guia(TipoGuiaFiscal.DARF, StatusGuiaFiscal.VENCIDA, "300.00")));

    assertThat(service.listarVencimentos(produtor.getId(), null, null).get(0).status())
        .isEqualTo("vencido");
  }

  @Test
  @DisplayName("listarVencimentos() retorna lista vazia para produtor autenticado sem guias")
  void listarVencimentosRetornaVazio() {
    when(guiaFiscalRepository.findVencimentosNaJanela(
        produtor.getId(), INICIO_PADRAO, FIM_PADRAO))
        .thenReturn(List.of());

    assertThat(service.listarVencimentos(produtor.getId(), null, null)).isEmpty();
  }

  @Test
  @DisplayName("listarVencimentos() lanca 404 quando o produtorId do path nao bate com o "
      + "produtor autenticado (anti-IDOR)")
  void listarVencimentosLancaNotFoundParaProdutorDiferenteDoAutenticado() {
    UUID produtorIdSolicitado = UUID.randomUUID();
    // O resolver e quem decide o acesso desde a #93; aqui ele nega, como faria para um produtorId
    // que nao e o do autenticado nem esta na carteira do contador.
    org.mockito.Mockito.doThrow(new ResourceNotFoundException("Produtor", produtorIdSolicitado))
        .when(produtorEmContexto).exigirAcesso(produtorIdSolicitado);

    assertThatThrownBy(() -> service.listarVencimentos(produtorIdSolicitado, null, null))
        .isInstanceOf(ResourceNotFoundException.class);

    verifyNoInteractions(guiaFiscalRepository);
  }

  // -------------------------------------------------------------------------
  // Janela do calendario (issue #103)
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("Sem de/ate usa a janela padrao: mes corrente -12 a +12 meses (issue #103)")
  void semParametrosUsaJanelaPadrao() {
    when(guiaFiscalRepository.findVencimentosNaJanela(
        any(), any(), any()))
        .thenReturn(List.of());

    service.listarVencimentos(produtor.getId(), null, null);

    verify(guiaFiscalRepository).findVencimentosNaJanela(
        produtor.getId(), INICIO_PADRAO, FIM_PADRAO);
  }

  @Test
  @DisplayName("de/ate informados sao repassados como janela inclusiva (issue #103)")
  void janelaInformadaERepassada() {
    LocalDate de = LocalDate.of(2026, 7, 1);
    LocalDate ate = LocalDate.of(2026, 9, 30);
    when(guiaFiscalRepository.findVencimentosNaJanela(
        produtor.getId(), de, ate))
        .thenReturn(List.of());

    assertThat(service.listarVencimentos(produtor.getId(), de, ate)).isEmpty();
  }

  @Test
  @DisplayName("So de informado: ate usa o padrao (issue #103)")
  void soDeInformadoUsaAtePadrao() {
    LocalDate de = LocalDate.of(2026, 1, 1);
    when(guiaFiscalRepository.findVencimentosNaJanela(
        produtor.getId(), de, FIM_PADRAO))
        .thenReturn(List.of());

    assertThat(service.listarVencimentos(produtor.getId(), de, null)).isEmpty();
  }

  @Test
  @DisplayName("de posterior a ate e rejeitado com 400 no campo 'de' (issue #103)")
  void dePosteriorAAteRejeitado() {

    assertThatThrownBy(() -> service.listarVencimentos(
        produtor.getId(), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 9, 30)))
        .isInstanceOfSatisfying(JanelaCalendarioInvalidaException.class, e -> {
          assertThat(e.getErrors()).singleElement().satisfies(erro -> {
            assertThat(erro.code()).isEqualTo(ErrorCode.JANELA_CALENDARIO_INVALIDA.code());
            assertThat(erro.field()).isEqualTo("de");
          });
        });
    verifyNoInteractions(guiaFiscalRepository);
  }

  @Test
  @DisplayName("Janela de exatamente 36 meses e aceita (borda inclusiva, issue #103)")
  void janelaDe36MesesAceita() {
    LocalDate de = LocalDate.of(2024, 1, 1);
    LocalDate ate = LocalDate.of(2026, 12, 31);
    when(guiaFiscalRepository.findVencimentosNaJanela(
        produtor.getId(), de, ate))
        .thenReturn(List.of());

    assertThat(service.listarVencimentos(produtor.getId(), de, ate)).isEmpty();
  }

  @Test
  @DisplayName("Janela acima de 36 meses e rejeitada com 400 no campo 'ate' (issue #103)")
  void janelaAcimaDoMaximoRejeitada() {

    assertThatThrownBy(() -> service.listarVencimentos(
        produtor.getId(), LocalDate.of(2024, 1, 1), LocalDate.of(2027, 1, 1)))
        .isInstanceOfSatisfying(JanelaCalendarioInvalidaException.class, e -> {
          assertThat(e.getErrors()).singleElement().satisfies(erro -> {
            assertThat(erro.field()).isEqualTo("ate");
            assertThat(erro.expectedValue()).isEqualTo("ate 2026-12-31");
          });
        });
    verify(guiaFiscalRepository, never()).findVencimentosNaJanela(any(), any(), any());
  }

  @Test
  @DisplayName("Janela de 36 meses iniciada em 29/02 cabe no maximo (fim de mes, issue #103)")
  void janelaIniciadaEm29DeFevereiroCabe() {
    LocalDate de = LocalDate.of(2024, 2, 29);
    LocalDate ate = LocalDate.of(2027, 2, 28);
    when(guiaFiscalRepository.findVencimentosNaJanela(produtor.getId(), de, ate))
        .thenReturn(List.of());

    assertThat(service.listarVencimentos(produtor.getId(), de, ate)).isEmpty();
  }

  @Test
  @DisplayName("Datas extremas nao estouram o calculo da janela (sem 500, issue #103)")
  void datasExtremasNaoEstouram() {
    LocalDate de = LocalDate.of(999_999_999, 1, 1);
    LocalDate ate = LocalDate.MAX;
    when(guiaFiscalRepository.findVencimentosNaJanela(produtor.getId(), de, ate))
        .thenReturn(List.of());

    assertThat(service.listarVencimentos(produtor.getId(), de, ate)).isEmpty();
  }

  @Test
  @DisplayName("So de muito antigo estoura o maximo com o ate padrao: 400 pede o ate (issue #103)")
  void soDeAntigoEstouraMaximo() {

    assertThatThrownBy(() -> service.listarVencimentos(
        produtor.getId(), LocalDate.of(2020, 1, 1), null))
        .isInstanceOf(JanelaCalendarioInvalidaException.class);
  }

  private GuiaFiscal guia(TipoGuiaFiscal tipo, StatusGuiaFiscal status, String valor) {
    return GuiaFiscal.builder()
        .id(UUID.randomUUID())
        .produtor(produtor)
        .tipoGuia(tipo)
        .dataVencimento(LocalDate.of(2026, 8, 20))
        .valor(new BigDecimal(valor))
        .status(status)
        .build();
  }
}
