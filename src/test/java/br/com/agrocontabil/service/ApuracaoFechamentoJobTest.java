package br.com.agrocontabil.service;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.YearMonth;
import java.time.ZoneId;
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
 * {@code America/Sao_Paulo}) — o gatilho por cron/condicionamento por propriedade é infra do
 * Spring e não é exercido aqui.
 */
@ExtendWith(MockitoExtension.class)
class ApuracaoFechamentoJobTest {

  @Mock private ApuracaoService apuracaoService;

  @InjectMocks private ApuracaoFechamentoJob job;

  @Test
  @DisplayName("Job fecha a apuracao do mes corrente (America/Sao_Paulo)")
  void fechaMesCorrente() {
    when(apuracaoService.fecharPeriodo(anyString())).thenReturn(3);

    job.fecharApuracaoDoMes();

    ArgumentCaptor<String> periodoCaptor = ArgumentCaptor.forClass(String.class);
    verify(apuracaoService).fecharPeriodo(periodoCaptor.capture());

    String esperado = YearMonth.now(ZoneId.of("America/Sao_Paulo")).toString();
    org.assertj.core.api.Assertions.assertThat(periodoCaptor.getValue()).isEqualTo(esperado);
  }
}
