package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.model.GuiaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusGuiaFiscal;
import br.com.agrocontabil.model.TipoGuiaFiscal;
import br.com.agrocontabil.repository.GuiaFiscalRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Testes unitários do {@link GuiaFiscalStatusSyncJob} (issue #74).
 *
 * <p>Verifica que apenas guias PENDENTE com vencimento anterior a hoje são transicionadas para
 * VENCIDA, e que o job não toca o repositório quando não há nenhuma vencida.
 */
@ExtendWith(MockitoExtension.class)
class GuiaFiscalStatusSyncJobTest {

  @Mock private GuiaFiscalRepository guiaFiscalRepository;

  @Captor private ArgumentCaptor<List<GuiaFiscal>> guiasCaptor;

  private static final Clock CLOCK_FIXO = Clock.fixed(
      Instant.parse("2026-07-15T12:00:00Z"), ZoneId.of("America/Sao_Paulo"));

  private GuiaFiscalStatusSyncJob job;

  private GuiaFiscal guiaPendente(LocalDate vencimento) {
    return GuiaFiscal.builder()
        .id(UUID.randomUUID())
        .produtor(Produtor.builder().id(UUID.randomUUID()).build())
        .tipoGuia(TipoGuiaFiscal.ICMS)
        .dataVencimento(vencimento)
        .valor(new BigDecimal("100.00"))
        .status(StatusGuiaFiscal.PENDENTE)
        .build();
  }

  @Test
  @DisplayName("sincronizarVencidas() marca como VENCIDA as guias pendentes com vencimento passado")
  void marcaGuiasPendentesVencidasComoVencida() {
    job = new GuiaFiscalStatusSyncJob(guiaFiscalRepository, CLOCK_FIXO);
    LocalDate hoje = LocalDate.now(CLOCK_FIXO);
    GuiaFiscal vencida1 = guiaPendente(hoje.minusDays(5));
    GuiaFiscal vencida2 = guiaPendente(hoje.minusDays(1));
    when(guiaFiscalRepository.findByStatusAndDataVencimentoBefore(StatusGuiaFiscal.PENDENTE, hoje))
        .thenReturn(List.of(vencida1, vencida2));

    job.sincronizarVencidas();

    verify(guiaFiscalRepository).saveAll(guiasCaptor.capture());
    assertThat(guiasCaptor.getValue())
        .extracting(GuiaFiscal::getStatus)
        .containsExactly(StatusGuiaFiscal.VENCIDA, StatusGuiaFiscal.VENCIDA);
  }

  @Test
  @DisplayName("sincronizarVencidas() nao chama saveAll quando nao ha guias vencidas")
  void naoChamaSaveAllQuandoNaoHaVencidas() {
    job = new GuiaFiscalStatusSyncJob(guiaFiscalRepository, CLOCK_FIXO);
    when(guiaFiscalRepository.findByStatusAndDataVencimentoBefore(
        eq(StatusGuiaFiscal.PENDENTE), any(LocalDate.class)))
        .thenReturn(List.of());

    job.sincronizarVencidas();

    verify(guiaFiscalRepository, never()).saveAll(any());
  }
}
