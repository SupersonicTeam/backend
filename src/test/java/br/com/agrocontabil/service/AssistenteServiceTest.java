package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.assistente.ContextoAssistente;
import br.com.agrocontabil.assistente.ResultadoRota;
import br.com.agrocontabil.assistente.RotaDados;
import br.com.agrocontabil.assistente.llm.ModeloLinguagem;
import br.com.agrocontabil.config.AssistenteProperties;
import br.com.agrocontabil.dto.AssistenteChatRequest;
import br.com.agrocontabil.dto.AssistenteChatResponse;
import br.com.agrocontabil.exception.AssistenteIndisponivelException;
import br.com.agrocontabil.exception.AssistenteOcupadoException;
import br.com.agrocontabil.exception.PapelNaoAutorizadoException;
import br.com.agrocontabil.model.DesfechoInteracao;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.security.PapelUsuario;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Testes do {@link AssistenteService} (issues #142 e #144): identidade primeiro, vagas limitadas,
 * auditoria de toda pergunta e resposta retida se a auditoria falhar.
 */
@ExtendWith(MockitoExtension.class)
class AssistenteServiceTest {

  @Mock private AuthenticatedProdutorProvider produtorProvider;
  @Mock private RotaDados rotaDados;
  @Mock private AuditoriaAssistenteService auditoria;
  @Mock private ModeloLinguagem modeloLinguagem;

  private final Produtor produtor = Produtor.builder().id(UUID.randomUUID()).build();
  private AssistenteService service;

  @BeforeEach
  void setUp() {
    AssistenteProperties propriedades = new AssistenteProperties();
    propriedades.setMaxConversasSimultaneas(1);
    // 29/09/2026 02:00 UTC = 28/09 23:00 em Brasilia: "hoje" da Ceres e o dia de Brasilia.
    Clock clock = Clock.fixed(Instant.parse("2026-09-29T02:00:00Z"), ZoneOffset.UTC);
    service = new AssistenteService(
        produtorProvider, rotaDados, auditoria, modeloLinguagem, clock, propriedades);
  }

  @Test
  @DisplayName("Contexto com o produtor autenticado e 'hoje' em Brasilia; auditoria RESPONDIDA")
  void contextoDoProdutorAutenticado() {
    when(produtorProvider.get()).thenReturn(produtor);
    when(modeloLinguagem.identificacao()).thenReturn("ollama/qwen3:4b");
    AtomicReference<ContextoAssistente> recebido = new AtomicReference<>();
    when(rotaDados.responder(anyString(), any())).thenAnswer(inv -> {
      recebido.set(inv.getArgument(1));
      return ResultadoRota.aceita("ok");
    });

    AssistenteChatResponse resposta = service.conversar(new AssistenteChatRequest("oi Ceres"));

    assertThat(resposta.rota()).isEqualTo("DADOS");
    assertThat(recebido.get().produtorId()).isEqualTo(produtor.getId());
    assertThat(recebido.get().hoje()).isEqualTo(LocalDate.of(2026, 9, 28));
    // So o tamanho da pergunta vai a auditoria, nunca o texto.
    verify(auditoria).registrar(eq(recebido.get()), eq("DADOS"),
        eq(DesfechoInteracao.RESPONDIDA), eq("ollama/qwen3:4b"), anyLong(), eq(8));
  }

  @Test
  @DisplayName("Resposta descartada nao exibe fontesDados e e auditada com o motivo")
  void descartadaSemFontes() {
    when(produtorProvider.get()).thenReturn(produtor);
    when(rotaDados.responder(anyString(), any())).thenAnswer(inv -> {
      ContextoAssistente contexto = inv.getArgument(1);
      contexto.registrarFonte("consultarApuracao", Map.of("mes", "2026-07"));
      return ResultadoRota.descartada("Nao consegui confirmar os valores.",
          DesfechoInteracao.NUMERO_SEM_FONTE);
    });

    AssistenteChatResponse resposta = service.conversar(new AssistenteChatRequest("oi"));

    assertThat(resposta.fontesDados()).isEmpty();
    verify(auditoria).registrar(any(), eq("DADOS"), eq(DesfechoInteracao.NUMERO_SEM_FONTE),
        any(), anyLong(), anyInt());
  }

  @Test
  @DisplayName("RF020: se a auditoria nao grava, a resposta nao e entregue (503)")
  void auditoriaFalhaRetemResposta() {
    when(produtorProvider.get()).thenReturn(produtor);
    when(rotaDados.responder(anyString(), any())).thenReturn(ResultadoRota.aceita("R$ 10,00"));
    doThrow(new IllegalStateException("banco fora")).when(auditoria)
        .registrar(any(), any(), any(), any(), anyLong(), anyInt());

    assertThatThrownBy(() -> service.conversar(new AssistenteChatRequest("oi")))
        .isInstanceOf(AssistenteIndisponivelException.class);
  }

  @Test
  @DisplayName("Modelo fora: auditado como MODELO_INDISPONIVEL e o 503 original sobe")
  void modeloForaAuditado() {
    when(produtorProvider.get()).thenReturn(produtor);
    AssistenteIndisponivelException fora = new AssistenteIndisponivelException("fora", null);
    when(rotaDados.responder(anyString(), any())).thenThrow(fora);

    assertThatThrownBy(() -> service.conversar(new AssistenteChatRequest("oi"))).isSameAs(fora);
    verify(auditoria).registrar(any(), eq("DADOS"), eq(DesfechoInteracao.MODELO_INDISPONIVEL),
        any(), anyLong(), anyInt());
  }

  @Test
  @DisplayName("Falha da auditoria de uma pergunta que ja falhou nao esconde a falha original")
  void auditoriaDeFalhaNaoMascaraErro() {
    when(produtorProvider.get()).thenReturn(produtor);
    IllegalStateException bug = new IllegalStateException("bug");
    when(rotaDados.responder(anyString(), any())).thenThrow(bug);
    doThrow(new IllegalStateException("banco fora")).when(auditoria)
        .registrar(any(), any(), any(), any(), anyLong(), anyInt());

    assertThatThrownBy(() -> service.conversar(new AssistenteChatRequest("oi"))).isSameAs(bug);
  }

  @Test
  @DisplayName("Contador: 403 antes de ocupar vaga, chamar a rota ou auditar")
  void contadorBarradoAntes() {
    when(produtorProvider.get()).thenThrow(
        new PapelNaoAutorizadoException(PapelUsuario.CONTADOR, PapelUsuario.PRODUTOR));

    assertThatThrownBy(() -> service.conversar(new AssistenteChatRequest("oi")))
        .isInstanceOf(PapelNaoAutorizadoException.class);
    verifyNoInteractions(rotaDados, auditoria);
  }

  @Test
  @DisplayName("Acima do limite de perguntas simultaneas: 429; a vaga volta depois")
  void limiteDeConcorrencia() throws Exception {
    when(produtorProvider.get()).thenReturn(produtor);
    CountDownLatch dentro = new CountDownLatch(1);
    CountDownLatch liberar = new CountDownLatch(1);
    when(rotaDados.responder(anyString(), any())).thenAnswer(inv -> {
      dentro.countDown();
      liberar.await(5, TimeUnit.SECONDS);
      return ResultadoRota.aceita("ok");
    });

    CompletableFuture<AssistenteChatResponse> primeira =
        CompletableFuture.supplyAsync(() -> service.conversar(new AssistenteChatRequest("1")));
    assertThat(dentro.await(5, TimeUnit.SECONDS)).isTrue();

    assertThatThrownBy(() -> service.conversar(new AssistenteChatRequest("2")))
        .isInstanceOf(AssistenteOcupadoException.class);

    liberar.countDown();
    assertThat(primeira.get(5, TimeUnit.SECONDS).resposta()).isEqualTo("ok");
    assertThat(service.conversar(new AssistenteChatRequest("3")).resposta()).isEqualTo("ok");
  }

  @Test
  @DisplayName("Falha do modelo devolve a vaga (sem vazamento de permissoes do semaforo)")
  void falhaDevolveVaga() {
    when(produtorProvider.get()).thenReturn(produtor);
    when(rotaDados.responder(anyString(), any()))
        .thenThrow(new AssistenteIndisponivelException("fora", null))
        .thenReturn(ResultadoRota.aceita("ok"));

    assertThatThrownBy(() -> service.conversar(new AssistenteChatRequest("1")))
        .isInstanceOf(AssistenteIndisponivelException.class);
    assertThat(service.conversar(new AssistenteChatRequest("2")).resposta()).isEqualTo("ok");
  }
}
