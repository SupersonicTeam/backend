package br.com.agrocontabil.assistente.auditoria;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import br.com.agrocontabil.assistente.ContextoAssistente;
import br.com.agrocontabil.assistente.porta.AuditoriaPorta;
import br.com.agrocontabil.assistente.porta.AuditoriaPorta.RegistroInteracao;
import br.com.agrocontabil.model.DesfechoInteracao;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Registro de auditoria montado pela {@link AuditoriaAssistente}. */
@ExtendWith(MockitoExtension.class)
class AuditoriaAssistenteTest {

  @Mock private AuditoriaPorta porta;

  private AuditoriaAssistente auditoria;
  private ContextoAssistente contexto;

  @BeforeEach
  void setUp() {
    auditoria = new AuditoriaAssistente(porta, new ObjectMapper(),
        Clock.fixed(Instant.parse("2026-10-14T12:00:00Z"), ZoneOffset.UTC));
    contexto = new ContextoAssistente(UUID.randomUUID(), LocalDate.of(2026, 10, 14));
  }

  @Test
  @DisplayName("MISTO: o desfecho de cada parte e o uso da pergunta reescrita vao ao registro")
  void mistoRegistraPartes() {
    contexto.registrarDesfechoDados(DesfechoInteracao.RESPONDIDA);
    contexto.registrarDesfechoLegislacao(DesfechoInteracao.CITACAO_INVALIDA, true);

    auditoria.registrar(contexto, "MISTO", DesfechoInteracao.RESPONDIDA, "none/simulado", 10, 80);

    RegistroInteracao r = capturar();
    assertThat(r.desfecho()).isEqualTo(DesfechoInteracao.RESPONDIDA);
    assertThat(r.desfechoDados()).isEqualTo(DesfechoInteracao.RESPONDIDA);
    assertThat(r.desfechoLegislacao()).isEqualTo(DesfechoInteracao.CITACAO_INVALIDA);
    assertThat(r.perguntaLeiReescrita()).isTrue();
  }

  @Test
  @DisplayName("Rota de uma parte so: campos das partes nulos")
  void rotaSimplesSemPartes() {
    auditoria.registrar(contexto, "DADOS", DesfechoInteracao.RESPONDIDA, "none/simulado", 10, 80);

    RegistroInteracao r = capturar();
    assertThat(r.desfechoDados()).isNull();
    assertThat(r.desfechoLegislacao()).isNull();
    assertThat(r.perguntaLeiReescrita()).isNull();
  }

  private RegistroInteracao capturar() {
    ArgumentCaptor<RegistroInteracao> captor = ArgumentCaptor.forClass(RegistroInteracao.class);
    verify(porta).registrar(captor.capture());
    return captor.getValue();
  }
}
