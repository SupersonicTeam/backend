package br.com.agrocontabil.sefaz;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.service.SefazService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
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

  @Test
  @DisplayName("o job reconcilia transmissoes presas ANTES de sincronizar os pendentes")
  void deveReconciliarPresasAntesDeSincronizar() {
    // issue #96: a ordem e o contrato. A reconciliacao move nota presa em ENVIANDO para
    // PENDENTE_SEFAZ; rodando primeiro, essas notas ja entram na sincronizacao da MESMA execucao
    // e o ciclo fecha sem esperar o proximo disparo (15 min depois).
    when(sefazService.reconciliarTransmissoesPresas()).thenReturn(1);
    when(sefazService.sincronizarPendentes()).thenReturn(1);

    job.sincronizarPendentes();

    InOrder ordem = inOrder(sefazService);
    ordem.verify(sefazService).reconciliarTransmissoesPresas();
    ordem.verify(sefazService).sincronizarPendentes();
  }
}
