package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Testes unitários do {@link ApuracaoFechamentoJob} (issue Agrocontabil#41 — RN004).
 *
 * <p>Verifica que o job dispara o fechamento do serviço para o mês corrente (fuso
 * {@code America/Sao_Paulo}) e que uma falha parcial (issue #100) não interrompe o refechamento —
 * o gatilho por cron/condicionamento por propriedade é infra do Spring e não é exercido aqui.
 */
@ExtendWith(MockitoExtension.class)
class ApuracaoFechamentoJobTest {

  private static final ResumoLoteApuracao SEM_FALHAS = new ResumoLoteApuracao(3, List.of());

  @Mock private ApuracaoService apuracaoService;

  @InjectMocks private ApuracaoFechamentoJob job;

  @Test
  @DisplayName("Job fecha a apuracao do mes corrente (America/Sao_Paulo)")
  void fechaMesCorrente() {
    when(apuracaoService.fecharPeriodo(anyString())).thenReturn(SEM_FALHAS);
    when(apuracaoService.refecharPeriodosReabertos()).thenReturn(SEM_FALHAS);

    job.fecharApuracaoDoMes();

    ArgumentCaptor<String> periodoCaptor = ArgumentCaptor.forClass(String.class);
    verify(apuracaoService).fecharPeriodo(periodoCaptor.capture());

    String esperado = YearMonth.now(ZoneId.of("America/Sao_Paulo")).toString();
    assertThat(periodoCaptor.getValue()).isEqualTo(esperado);
  }

  @Test
  @DisplayName("Job tambem refecha periodos reabertos de meses anteriores (issue #57)")
  void refechaPeriodosReabertos() {
    when(apuracaoService.fecharPeriodo(anyString())).thenReturn(SEM_FALHAS);
    when(apuracaoService.refecharPeriodosReabertos()).thenReturn(SEM_FALHAS);

    job.fecharApuracaoDoMes();

    verify(apuracaoService).refecharPeriodosReabertos();
  }

  @Test
  @DisplayName("Falha parcial no fechamento nao impede o refechamento (issue #100)")
  void falhaParcialNaoImpedeRefechamento() {
    ResumoLoteApuracao comFalha = new ResumoLoteApuracao(2, List.of(UUID.randomUUID()));
    when(apuracaoService.fecharPeriodo(anyString())).thenReturn(comFalha);
    when(apuracaoService.refecharPeriodosReabertos()).thenReturn(SEM_FALHAS);

    job.fecharApuracaoDoMes();

    verify(apuracaoService).refecharPeriodosReabertos();
  }
}
