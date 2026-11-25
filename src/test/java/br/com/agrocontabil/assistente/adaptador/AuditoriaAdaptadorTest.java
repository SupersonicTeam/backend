package br.com.agrocontabil.assistente.adaptador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.assistente.porta.AuditoriaPorta.RegistroInteracao;
import br.com.agrocontabil.model.AssistenteInteracao;
import br.com.agrocontabil.model.DesfechoInteracao;
import br.com.agrocontabil.model.EstadoEmbeddingClassificacao;
import br.com.agrocontabil.service.AssistenteInteracaoService;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Conversão do registro da porta na entidade de auditoria da Ceres. */
class AuditoriaAdaptadorTest {

  private final AssistenteInteracaoService service = mock(AssistenteInteracaoService.class);
  private final AuditoriaAdaptador adaptador = new AuditoriaAdaptador(service);

  private static RegistroInteracao registro(EstadoEmbeddingClassificacao estado,
      Integer latenciaMs) {
    return new RegistroInteracao(UUID.randomUUID(), "DADOS", DesfechoInteracao.RESPONDIDA,
        null, null, null, "LLM", null, 1, null, "anthropic/simulado", 900L, 100, 20, false, 30,
        OffsetDateTime.now(), null, null, null, null, null, estado, latenciaMs);
  }

  private AssistenteInteracao gravada(RegistroInteracao r) {
    when(service.registrar(any())).thenReturn(UUID.randomUUID());
    adaptador.registrar(r);
    ArgumentCaptor<AssistenteInteracao> captor = ArgumentCaptor.forClass(AssistenteInteracao.class);
    verify(service).registrar(captor.capture());
    return captor.getValue();
  }

  @Test
  @DisplayName("#624: o estado e a espera do embedding da classificacao chegam a entidade")
  void embeddingDaClassificacao() {
    AssistenteInteracao a = gravada(registro(EstadoEmbeddingClassificacao.EM_PAUSA, null));

    assertThat(a.getCamadaClassificacao()).isEqualTo("LLM");
    assertThat(a.getEmbeddingClassificacao()).isEqualTo(EstadoEmbeddingClassificacao.EM_PAUSA);
    assertThat(a.getLatenciaEmbeddingClassificacaoMs()).isNull();
  }

  @Test
  @DisplayName("#624: com o embedding respondido, a espera tambem vai")
  void comLatencia() {
    AssistenteInteracao a = gravada(registro(EstadoEmbeddingClassificacao.RESPONDEU, 48));

    assertThat(a.getEmbeddingClassificacao()).isEqualTo(EstadoEmbeddingClassificacao.RESPONDEU);
    assertThat(a.getLatenciaEmbeddingClassificacaoMs()).isEqualTo(48);
  }
}
