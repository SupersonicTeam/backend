package br.com.agrocontabil.sefaz;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.service.SefazService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Testes unitarios do {@link SefazStatusSyncJob}.
 *
 * <p>Verifica que o job delega a sincronizacao ao {@link SefazService}.
 */
@ExtendWith(MockitoExtension.class)
class SefazStatusSyncJobTest {

  @Mock
  private SefazService sefazService;

  @InjectMocks
  private SefazStatusSyncJob job;

  @Test
  @DisplayName("sincronizarPendentes deve delegar ao SefazService")
  void sincronizarPendentesShouldDelegateToService() {
    when(sefazService.sincronizarPendentes()).thenReturn(2);

    job.sincronizarPendentes();

    verify(sefazService).sincronizarPendentes();
  }
}
