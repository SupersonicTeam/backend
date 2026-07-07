package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.dto.VencimentoFiscalResponse;
import br.com.agrocontabil.exception.ResourceNotFoundException;
import br.com.agrocontabil.model.GuiaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusGuiaFiscal;
import br.com.agrocontabil.model.TipoGuiaFiscal;
import br.com.agrocontabil.repository.GuiaFiscalRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Testes unitários do {@link CalendarioFiscalService} (issue #74).
 *
 * <p>Verifica o mapeamento das guias fiscais do produtor para o item do calendário — em
 * particular a conversão de {@link StatusGuiaFiscal} para o status em minúsculas esperado pelo
 * frontend (pendente/pago/vencido) e o {@code tipo} fixo {@code "imposto"} — e o anti-IDOR: o
 * {@code produtorId} do path precisa bater com o produtor autenticado (PR #75, revisão Copilot).
 */
@ExtendWith(MockitoExtension.class)
class CalendarioFiscalServiceTest {

  @Mock private GuiaFiscalRepository guiaFiscalRepository;
  @Mock private AuthenticatedProdutorProvider produtorProvider;

  private CalendarioFiscalService service;

  @BeforeEach
  void setUp() {
    service = new CalendarioFiscalService(guiaFiscalRepository, produtorProvider);
  }

  private Produtor produtor() {
    return Produtor.builder().id(UUID.randomUUID()).nomeCompleto("Fazenda Boa Vista").build();
  }

  @Test
  @DisplayName("listarVencimentos() mapeia guia PAGA para status 'pago' e tipo 'imposto'")
  void mapeiaGuiaPagaParaVencimentoPago() {
    Produtor produtor = produtor();
    when(produtorProvider.get()).thenReturn(produtor);
    GuiaFiscal guia = GuiaFiscal.builder()
        .id(UUID.randomUUID())
        .produtor(produtor)
        .tipoGuia(TipoGuiaFiscal.ICMS)
        .dataVencimento(LocalDate.of(2026, 8, 20))
        .valor(new BigDecimal("1250.00"))
        .status(StatusGuiaFiscal.PAGA)
        .build();
    when(guiaFiscalRepository.findByProdutorIdOrderByDataVencimentoDesc(produtor.getId()))
        .thenReturn(List.of(guia));

    List<VencimentoFiscalResponse> vencimentos = service.listarVencimentos(produtor.getId());

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
    Produtor produtor = produtor();
    when(produtorProvider.get()).thenReturn(produtor);
    GuiaFiscal guia = GuiaFiscal.builder()
        .id(UUID.randomUUID())
        .produtor(produtor)
        .tipoGuia(TipoGuiaFiscal.DARF)
        .dataVencimento(LocalDate.of(2026, 6, 10))
        .valor(new BigDecimal("300.00"))
        .status(StatusGuiaFiscal.VENCIDA)
        .build();
    when(guiaFiscalRepository.findByProdutorIdOrderByDataVencimentoDesc(produtor.getId()))
        .thenReturn(List.of(guia));

    assertThat(service.listarVencimentos(produtor.getId()).get(0).status()).isEqualTo("vencido");
  }

  @Test
  @DisplayName("listarVencimentos() retorna lista vazia para produtor autenticado sem guias")
  void listarVencimentosRetornaVazio() {
    Produtor produtor = produtor();
    when(produtorProvider.get()).thenReturn(produtor);
    when(guiaFiscalRepository.findByProdutorIdOrderByDataVencimentoDesc(produtor.getId()))
        .thenReturn(List.of());

    assertThat(service.listarVencimentos(produtor.getId())).isEmpty();
  }

  @Test
  @DisplayName("listarVencimentos() lanca 404 quando o produtorId do path nao bate com o "
      + "produtor autenticado (anti-IDOR)")
  void listarVencimentosLancaNotFoundParaProdutorDiferenteDoAutenticado() {
    Produtor autenticado = produtor();
    UUID produtorIdSolicitado = UUID.randomUUID();
    when(produtorProvider.get()).thenReturn(autenticado);

    assertThatThrownBy(() -> service.listarVencimentos(produtorIdSolicitado))
        .isInstanceOf(ResourceNotFoundException.class);

    verify(guiaFiscalRepository, never()).findByProdutorIdOrderByDataVencimentoDesc(any());
  }
}
