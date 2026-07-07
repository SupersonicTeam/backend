package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.dto.VencimentoFiscalResponse;
import br.com.agrocontabil.model.GuiaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusGuiaFiscal;
import br.com.agrocontabil.model.TipoGuiaFiscal;
import br.com.agrocontabil.repository.GuiaFiscalRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
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
 * frontend (pendente/pago/vencido) e o {@code tipo} fixo {@code "imposto"}.
 */
@ExtendWith(MockitoExtension.class)
class CalendarioFiscalServiceTest {

  @Mock private GuiaFiscalRepository guiaFiscalRepository;

  private CalendarioFiscalService service;

  private Produtor produtor() {
    return Produtor.builder().id(UUID.randomUUID()).nomeCompleto("Fazenda Boa Vista").build();
  }

  @Test
  @DisplayName("listarVencimentos() mapeia guia PAGA para status 'pago' e tipo 'imposto'")
  void mapeiaGuiaPagaParaVencimentoPago() {
    service = new CalendarioFiscalService(guiaFiscalRepository);
    Produtor produtor = produtor();
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
    service = new CalendarioFiscalService(guiaFiscalRepository);
    Produtor produtor = produtor();
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
  @DisplayName("listarVencimentos() retorna lista vazia para produtor sem guias")
  void listarVencimentosRetornaVazio() {
    service = new CalendarioFiscalService(guiaFiscalRepository);
    UUID produtorId = UUID.randomUUID();
    when(guiaFiscalRepository.findByProdutorIdOrderByDataVencimentoDesc(produtorId))
        .thenReturn(List.of());

    assertThat(service.listarVencimentos(produtorId)).isEmpty();
  }
}
