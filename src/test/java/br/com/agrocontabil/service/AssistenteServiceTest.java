package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.assistente.ContextoAssistente;
import br.com.agrocontabil.assistente.RotaDados;
import br.com.agrocontabil.config.AssistenteProperties;
import br.com.agrocontabil.dto.AssistenteChatRequest;
import br.com.agrocontabil.dto.AssistenteChatResponse;
import br.com.agrocontabil.exception.AssistenteIndisponivelException;
import br.com.agrocontabil.exception.AssistenteOcupadoException;
import br.com.agrocontabil.exception.PapelNaoAutorizadoException;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.security.PapelUsuario;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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

/** Testes do {@link AssistenteService} (issue #142): identidade primeiro, vagas limitadas. */
@ExtendWith(MockitoExtension.class)
class AssistenteServiceTest {

  @Mock private AuthenticatedProdutorProvider produtorProvider;
  @Mock private RotaDados rotaDados;

  private final Produtor produtor = Produtor.builder().id(UUID.randomUUID()).build();
  private AssistenteService service;

  @BeforeEach
  void setUp() {
    AssistenteProperties propriedades = new AssistenteProperties();
    propriedades.setMaxConversasSimultaneas(1);
    // 29/09/2026 02:00 UTC = 28/09 23:00 em Brasilia: "hoje" da Ceres e o dia de Brasilia.
    Clock clock = Clock.fixed(Instant.parse("2026-09-29T02:00:00Z"), ZoneOffset.UTC);
    service = new AssistenteService(produtorProvider, rotaDados, clock, propriedades);
  }

  @Test
  @DisplayName("Contexto com o produtor autenticado e 'hoje' em Brasilia; rota DADOS")
  void contextoDoProdutorAutenticado() {
    when(produtorProvider.get()).thenReturn(produtor);
    AtomicReference<ContextoAssistente> recebido = new AtomicReference<>();
    when(rotaDados.responder(anyString(), any())).thenAnswer(inv -> {
      recebido.set(inv.getArgument(1));
      return "ok";
    });

    AssistenteChatResponse resposta = service.conversar(new AssistenteChatRequest("oi"));

    assertThat(resposta.rota()).isEqualTo("DADOS");
    assertThat(recebido.get().produtorId()).isEqualTo(produtor.getId());
    assertThat(recebido.get().hoje()).isEqualTo(LocalDate.of(2026, 9, 28));
  }

  @Test
  @DisplayName("Contador: 403 antes de ocupar vaga ou chamar a rota")
  void contadorBarradoAntes() {
    when(produtorProvider.get()).thenThrow(
        new PapelNaoAutorizadoException(PapelUsuario.CONTADOR, PapelUsuario.PRODUTOR));

    assertThatThrownBy(() -> service.conversar(new AssistenteChatRequest("oi")))
        .isInstanceOf(PapelNaoAutorizadoException.class);
    verifyNoInteractions(rotaDados);
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
      return "ok";
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
        .thenReturn("ok");

    assertThatThrownBy(() -> service.conversar(new AssistenteChatRequest("1")))
        .isInstanceOf(AssistenteIndisponivelException.class);
    assertThat(service.conversar(new AssistenteChatRequest("2")).resposta()).isEqualTo("ok");
  }
}
