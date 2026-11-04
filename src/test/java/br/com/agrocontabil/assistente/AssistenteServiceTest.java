package br.com.agrocontabil.assistente;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.assistente.auditoria.AuditoriaAssistente;
import br.com.agrocontabil.assistente.llm.ModeloLinguagem;
import br.com.agrocontabil.assistente.orquestracao.Intencao;
import br.com.agrocontabil.assistente.orquestracao.OrquestradorService;
import br.com.agrocontabil.assistente.orquestracao.ResultadoOrquestracao;
import br.com.agrocontabil.assistente.porta.IdentidadePorta;
import br.com.agrocontabil.assistente.rag.CitacaoLegal;
import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.config.AssistenteProperties;
import br.com.agrocontabil.dto.AssistenteChatRequest;
import br.com.agrocontabil.dto.AssistenteChatResponse;
import br.com.agrocontabil.exception.AssistenteIndisponivelException;
import br.com.agrocontabil.exception.AssistenteOcupadoException;
import br.com.agrocontabil.exception.AssistenteTempoEsgotadoException;
import br.com.agrocontabil.exception.PapelNaoAutorizadoException;
import br.com.agrocontabil.model.DesfechoInteracao;
import br.com.agrocontabil.security.PapelUsuario;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Testes do {@link AssistenteService} (issues #142, #144 e #157): identidade primeiro, bulkhead
 * (global e por produtor), tempo total, auditoria de toda pergunta e resposta retida se a
 * auditoria falhar.
 */
@ExtendWith(MockitoExtension.class)
class AssistenteServiceTest {

  private static final String URL = "https://www.planalto.gov.br/ccivil_03/leis/lcp/lcp214.htm";

  @Mock private IdentidadePorta identidade;
  @Mock private OrquestradorService orquestrador;
  @Mock private AuditoriaAssistente auditoria;
  @Mock private ModeloLinguagem modeloLinguagem;

  private final UUID produtorId = UUID.randomUUID();
  private final CotaFalsa cota = new CotaFalsa();
  private AssistenteService service;

  private AssistenteService criar(int maxConversas, Duration timeoutPergunta) {
    AssistenteProperties propriedades = new AssistenteProperties();
    propriedades.setMaxConversasSimultaneas(maxConversas);
    propriedades.setTimeoutPergunta(timeoutPergunta);
    // 29/09/2026 02:00 UTC = 28/09 23:00 em Brasilia: "hoje" da Ceres e o dia de Brasilia.
    Clock clock = Clock.fixed(Instant.parse("2026-09-29T02:00:00Z"), ZoneOffset.UTC);
    service = new AssistenteService(
        identidade, cota, orquestrador, auditoria, modeloLinguagem, clock, propriedades);
    return service;
  }

  private AssistenteService criar() {
    return criar(1, Duration.ofSeconds(10));
  }

  @AfterEach
  void tearDown() {
    if (service != null) {
      service.destroy();
    }
  }

  private static ResultadoOrquestracao ok() {
    return new ResultadoOrquestracao(
        Intencao.DADOS, "ok", DesfechoInteracao.RESPONDIDA, List.of(), true);
  }

  @Test
  @DisplayName("Contexto do produtor autenticado; rota, citacoes e fontes no contrato; auditoria")
  void contratoCompleto() {
    criar();
    when(identidade.produtorAutenticado()).thenReturn(produtorId);
    when(modeloLinguagem.identificacao()).thenReturn("ollama/qwen3:4b");
    AtomicReference<ContextoAssistente> recebido = new AtomicReference<>();
    when(orquestrador.responder(anyString(), any(), any())).thenAnswer(inv -> {
      ContextoAssistente contexto = inv.getArgument(1);
      recebido.set(contexto);
      contexto.registrarFonte("consultarApuracao", Map.of("mes", "2026-07"), false);
      return new ResultadoOrquestracao(Intencao.MISTO, "texto", DesfechoInteracao.RESPONDIDA,
          List.of(new CitacaoLegal("LC 214/2025", "art. 138", URL, "trecho")), true);
    });

    AssistenteChatResponse resposta = service.conversar(new AssistenteChatRequest("oi Ceres"));

    assertThat(resposta.rota()).isEqualTo("MISTO");
    assertThat(resposta.citacoes()).singleElement()
        .satisfies(c -> assertThat(c.url()).isEqualTo(URL));
    assertThat(resposta.fontesDados()).extracting(AssistenteChatResponse.FonteDado::funcao)
        .containsExactly("consultarApuracao");
    assertThat(recebido.get().produtorId()).isEqualTo(produtorId);
    assertThat(recebido.get().hoje()).isEqualTo(LocalDate.of(2026, 9, 28));
    // So o tamanho da pergunta vai a auditoria, nunca o texto.
    verify(auditoria).registrar(eq(recebido.get()), eq("MISTO"),
        eq(DesfechoInteracao.RESPONDIDA), eq("ollama/qwen3:4b"), anyLong(), eq(8));
  }

  @Test
  @DisplayName("A pergunta roda no pool proprio da Ceres, nao na thread da requisicao")
  void rodaNoPoolProprio() {
    criar();
    when(identidade.produtorAutenticado()).thenReturn(produtorId);
    AtomicReference<String> thread = new AtomicReference<>();
    when(orquestrador.responder(anyString(), any(), any())).thenAnswer(inv -> {
      thread.set(Thread.currentThread().getName());
      return ok();
    });

    service.conversar(new AssistenteChatRequest("oi"));

    assertThat(thread.get()).startsWith(AssistenteService.PREFIXO_THREADS);
  }

  @Test
  @DisplayName("Parte de dados descartada: fontesDados nao aparece")
  void semFontesQuandoDadosDescartados() {
    criar();
    when(identidade.produtorAutenticado()).thenReturn(produtorId);
    when(orquestrador.responder(anyString(), any(), any())).thenAnswer(inv -> {
      ContextoAssistente contexto = inv.getArgument(1);
      contexto.registrarFonte("consultarApuracao", Map.of("mes", "2026-07"), false);
      return new ResultadoOrquestracao(Intencao.DADOS, "Nao consegui confirmar os valores.",
          DesfechoInteracao.NUMERO_SEM_FONTE, List.of(), false);
    });

    assertThat(service.conversar(new AssistenteChatRequest("oi")).fontesDados()).isEmpty();
  }

  @Test
  @DisplayName("RF020: se a auditoria nao grava, a resposta nao e entregue (503)")
  void auditoriaFalhaRetemResposta() {
    criar();
    when(identidade.produtorAutenticado()).thenReturn(produtorId);
    when(orquestrador.responder(anyString(), any(), any())).thenReturn(new ResultadoOrquestracao(
        Intencao.DADOS, "R$ 10,00", DesfechoInteracao.RESPONDIDA, List.of(), true));
    doThrow(new IllegalStateException("banco fora")).when(auditoria)
        .registrar(any(), any(), any(), any(), anyLong(), anyInt());

    assertThatThrownBy(() -> service.conversar(new AssistenteChatRequest("oi")))
        .isInstanceOf(AssistenteIndisponivelException.class);
  }

  @Test
  @DisplayName("Modelo fora antes da classificacao: auditado com rota INDEFINIDA")
  void modeloForaAntesDaRota() {
    criar();
    when(identidade.produtorAutenticado()).thenReturn(produtorId);
    AssistenteIndisponivelException fora = new AssistenteIndisponivelException("fora", null);
    when(orquestrador.responder(anyString(), any(), any())).thenThrow(fora);

    assertThatThrownBy(() -> service.conversar(new AssistenteChatRequest("oi"))).isSameAs(fora);
    verify(auditoria).registrar(any(), eq(AssistenteService.ROTA_INDEFINIDA),
        eq(DesfechoInteracao.MODELO_INDISPONIVEL), any(), anyLong(), anyInt());
  }

  @Test
  @DisplayName("Modelo fora depois da classificacao: auditado com a rota decidida")
  @SuppressWarnings("unchecked")
  void modeloForaDepoisDaRota() {
    criar();
    when(identidade.produtorAutenticado()).thenReturn(produtorId);
    when(orquestrador.responder(anyString(), any(), any())).thenAnswer(inv -> {
      ((Consumer<Intencao>) inv.getArgument(2)).accept(Intencao.LEGISLACAO);
      throw new AssistenteIndisponivelException("fora", null);
    });

    assertThatThrownBy(() -> service.conversar(new AssistenteChatRequest("oi")))
        .isInstanceOf(AssistenteIndisponivelException.class);
    verify(auditoria).registrar(any(), eq("LEGISLACAO"),
        eq(DesfechoInteracao.MODELO_INDISPONIVEL), any(), anyLong(), anyInt());
  }

  @Test
  @DisplayName("Falha da auditoria de uma pergunta que ja falhou nao esconde a falha original")
  void auditoriaDeFalhaNaoMascaraErro() {
    criar();
    when(identidade.produtorAutenticado()).thenReturn(produtorId);
    IllegalStateException bug = new IllegalStateException("bug");
    when(orquestrador.responder(anyString(), any(), any())).thenThrow(bug);
    doThrow(new IllegalStateException("banco fora")).when(auditoria)
        .registrar(any(), any(), any(), any(), anyLong(), anyInt());

    assertThatThrownBy(() -> service.conversar(new AssistenteChatRequest("oi"))).isSameAs(bug);
  }

  @Test
  @DisplayName("Contador: 403 antes de ocupar vaga, orquestrar ou auditar (sincrono e streaming)")
  void contadorBarradoAntes() {
    criar();
    when(identidade.produtorAutenticado()).thenThrow(
        new PapelNaoAutorizadoException(PapelUsuario.CONTADOR, PapelUsuario.PRODUTOR));

    assertThatThrownBy(() -> service.conversar(new AssistenteChatRequest("oi")))
        .isInstanceOf(PapelNaoAutorizadoException.class);
    assertThatThrownBy(() -> service.conversarEmStream(new AssistenteChatRequest("oi"), "/x"))
        .isInstanceOf(PapelNaoAutorizadoException.class);
    verifyNoInteractions(orquestrador, auditoria);
  }

  @Test
  @DisplayName("Limite global vale para sincrono e streaming somados, e a vaga volta ao terminar")
  void limiteGlobal() throws Exception {
    criar(1, Duration.ofSeconds(10));
    UUID outroProdutor = UUID.randomUUID();
    when(identidade.produtorAutenticado()).thenReturn(produtorId, outroProdutor, outroProdutor,
        outroProdutor);
    CountDownLatch dentro = new CountDownLatch(1);
    CountDownLatch liberar = new CountDownLatch(1);
    when(orquestrador.responder(anyString(), any(), any())).thenAnswer(inv -> {
      dentro.countDown();
      liberar.await(5, TimeUnit.SECONDS);
      return ok();
    });

    CompletableFuture<AssistenteChatResponse> primeira =
        CompletableFuture.supplyAsync(() -> service.conversar(new AssistenteChatRequest("1")));
    assertThat(dentro.await(5, TimeUnit.SECONDS)).isTrue();

    // Outro produtor, sem vaga global.
    assertThatThrownBy(() -> service.conversar(new AssistenteChatRequest("2")))
        .isInstanceOfSatisfying(AssistenteOcupadoException.class,
            e -> assertThat(e.getErrors().get(0).code())
                .isEqualTo(ErrorCode.ASSISTENTE_OCUPADO.name()));
    assertThatThrownBy(() -> service.conversarEmStream(new AssistenteChatRequest("3"), "/x"))
        .isInstanceOf(AssistenteOcupadoException.class);

    liberar.countDown();
    assertThat(primeira.get(5, TimeUnit.SECONDS).resposta()).isEqualTo("ok");
    assertThat(service.conversar(new AssistenteChatRequest("4")).resposta()).isEqualTo("ok");
  }

  @Test
  @DisplayName("Uma pergunta por produtor: a segunda do mesmo produtor recebe 503 mesmo com vaga")
  void umaPerguntaPorProdutor() throws Exception {
    criar(4, Duration.ofSeconds(10));
    UUID outroProdutor = UUID.randomUUID();
    when(identidade.produtorAutenticado())
        .thenReturn(produtorId, produtorId, outroProdutor, produtorId);
    CountDownLatch dentro = new CountDownLatch(1);
    CountDownLatch liberar = new CountDownLatch(1);
    when(orquestrador.responder(eq("1"), any(), any())).thenAnswer(inv -> {
      dentro.countDown();
      liberar.await(5, TimeUnit.SECONDS);
      return ok();
    });

    CompletableFuture<AssistenteChatResponse> primeira =
        CompletableFuture.supplyAsync(() -> service.conversar(new AssistenteChatRequest("1")));
    assertThat(dentro.await(5, TimeUnit.SECONDS)).isTrue();

    assertThatThrownBy(() -> service.conversar(new AssistenteChatRequest("2")))
        .isInstanceOf(AssistenteOcupadoException.class)
        .hasMessageContaining("pergunta em andamento");
    // Outro produtor tem vaga.
    when(orquestrador.responder(eq("3"), any(), any())).thenReturn(ok());
    assertThat(service.conversar(new AssistenteChatRequest("3")).resposta()).isEqualTo("ok");

    liberar.countDown();
    primeira.get(5, TimeUnit.SECONDS);
    // Terminada a primeira, o produtor volta a poder perguntar.
    when(orquestrador.responder(eq("4"), any(), any())).thenReturn(ok());
    assertThat(service.conversar(new AssistenteChatRequest("4")).resposta()).isEqualTo("ok");
  }

  @Test
  @DisplayName("Tempo total esgotado: 503, thread interrompida, auditoria TEMPO_ESGOTADO fora da "
      + "interrupcao e vaga devolvida")
  void tempoEsgotado() {
    criar(1, Duration.ofMillis(300));
    when(identidade.produtorAutenticado()).thenReturn(produtorId);
    AtomicBoolean interrompida = new AtomicBoolean();
    when(orquestrador.responder(eq("lenta"), any(), any())).thenAnswer(inv -> {
      try {
        new CountDownLatch(1).await(10, TimeUnit.SECONDS);
        return ok();
      } catch (InterruptedException e) {
        interrompida.set(true);
        // Como o cliente HTTP do modelo reage a interrupcao.
        throw new AssistenteIndisponivelException("interrompida", e);
      }
    });
    AtomicBoolean auditoriaEmThreadInterrompida = new AtomicBoolean(true);
    doAnswer(inv -> {
      auditoriaEmThreadInterrompida.set(Thread.currentThread().isInterrupted());
      return null;
    }).when(auditoria).registrar(any(), any(), eq(DesfechoInteracao.TEMPO_ESGOTADO), any(),
        anyLong(), anyInt());

    assertThatThrownBy(() -> service.conversar(new AssistenteChatRequest("lenta")))
        .isInstanceOf(AssistenteTempoEsgotadoException.class);

    verify(auditoria, timeout(5000)).registrar(any(), any(),
        eq(DesfechoInteracao.TEMPO_ESGOTADO), any(), anyLong(), anyInt());
    assertThat(interrompida).isTrue();
    assertThat(auditoriaEmThreadInterrompida).isFalse();
    // A vaga (global e do produtor) volta: a proxima pergunta e atendida.
    when(orquestrador.responder(eq("rapida"), any(), any())).thenReturn(ok());
    assertThat(perguntarQuandoHouverVaga("rapida").resposta()).isEqualTo("ok");
  }

  // --- cota semanal (#166) ---

  @Test
  @DisplayName("#166: pergunta concluida acerta pelos tokens do contexto, uma vez so")
  void perguntaConcluidaAcerta() {
    criar();
    when(identidade.produtorAutenticado()).thenReturn(produtorId);
    when(modeloLinguagem.identificacao()).thenReturn("ollama/qwen3");
    when(orquestrador.responder(any(), any(), any())).thenAnswer(invocacao -> {
      ContextoAssistente contexto = invocacao.getArgument(1);
      contexto.registrarUso(null, new org.springframework.ai.chat.model.ChatResponse(List.of()));
      return ok();
    });

    service.conversar(new AssistenteChatRequest("pergunta"));

    assertThat(cota.reservas).isEqualTo(1);
    assertThat(cota.acertos).isEqualTo(1);
    assertThat(cota.estornos).isZero();
    assertThat(cota.ultimoModelo).isEqualTo("ollama/qwen3");
    // Sem uso informado, os tokens foram estimados (minimo de 1 cada) e ainda assim cobrados.
    assertThat(cota.ultimaEntrada).isPositive();
    assertThat(cota.ultimaSaida).isPositive();
  }

  @Test
  @DisplayName("#166: falha na pergunta estorna a reserva inteira")
  void falhaEstorna() {
    criar();
    when(identidade.produtorAutenticado()).thenReturn(produtorId);
    when(orquestrador.responder(any(), any(), any()))
        .thenThrow(new AssistenteIndisponivelException("banco fora", null));

    assertThatThrownBy(() -> service.conversar(new AssistenteChatRequest("pergunta")))
        .isInstanceOf(AssistenteIndisponivelException.class);

    assertThat(cota.estornos).isEqualTo(1);
    assertThat(cota.acertos).isZero();
  }

  @Test
  @DisplayName("#166: sem credito, 429 antes de qualquer vaga ou chamada ao modelo")
  void semCreditoNemComeca() {
    criar();
    when(identidade.produtorAutenticado()).thenReturn(produtorId);
    cota.recusar = true;

    assertThatThrownBy(() -> service.conversar(new AssistenteChatRequest("pergunta")))
        .isInstanceOf(br.com.agrocontabil.exception.LimiteUsoAssistenteException.class);

    verifyNoInteractions(orquestrador, auditoria);
    assertThat(cota.acertos + cota.estornos).isZero();
  }

  @Test
  @DisplayName("#335: stream encerrado antes de o Spring assumir o emissor nao deixa o prazo "
      + "agendado")
  void streamRapidoCancelaOPrazo() {
    // Prazo longo: o que se prova e que a pergunta encerrada cancela o prazo, nao que ele vence.
    criar(1, Duration.ofMinutes(5));
    when(identidade.produtorAutenticado()).thenReturn(produtorId);
    when(orquestrador.responder(anyString(), any(), any())).thenReturn(ok());

    // Sem o Spring MVC, ninguem inicializa o emissor: e o caso da pergunta rapida, que termina
    // antes de o controller devolver o emissor. O onCompletion nunca roda.
    service.conversarEmStream(new AssistenteChatRequest("oi"), "/x");

    // A vaga volta no fim da tarefa, e o prazo e cancelado no mesmo ponto.
    perguntarQuandoHouverVaga("de novo");
    assertThat(service.prazosAgendados()).isZero();
  }

  @Test
  @DisplayName("#166: sem vaga (pergunta anterior em andamento) a reserva volta na hora")
  void semVagaEstorna() throws Exception {
    criar();
    when(identidade.produtorAutenticado()).thenReturn(produtorId);
    CountDownLatch dentro = new CountDownLatch(1);
    CountDownLatch solta = new CountDownLatch(1);
    when(orquestrador.responder(any(), any(), any())).thenAnswer(invocacao -> {
      dentro.countDown();
      solta.await(5, TimeUnit.SECONDS);
      return ok();
    });
    CompletableFuture<AssistenteChatResponse> primeira = CompletableFuture.supplyAsync(
        () -> service.conversar(new AssistenteChatRequest("primeira")));
    assertThat(dentro.await(5, TimeUnit.SECONDS)).isTrue();

    assertThatThrownBy(() -> service.conversar(new AssistenteChatRequest("segunda")))
        .isInstanceOf(AssistenteOcupadoException.class);
    assertThat(cota.estornos).isEqualTo(1);

    solta.countDown();
    primeira.get(5, TimeUnit.SECONDS);
    assertThat(cota.acertos).isEqualTo(1);
  }

  /** Porta da cota em memória: conta as chamadas (estornar e acertar nunca ambos por pergunta). */
  private static final class CotaFalsa implements br.com.agrocontabil.assistente.porta.CotaUsoPorta {

    volatile boolean recusar;
    volatile int reservas;
    volatile int acertos;
    volatile int estornos;
    volatile long ultimaEntrada;
    volatile long ultimaSaida;
    volatile String ultimoModelo;

    @Override
    public synchronized ReservaCota reservar(UUID produtorId, String modelo) {
      if (recusar) {
        throw new br.com.agrocontabil.exception.LimiteUsoAssistenteException(60);
      }
      reservas++;
      return new ReservaCota(produtorId, java.time.LocalDate.of(2026, 9, 28), 73, true);
    }

    @Override
    public synchronized long acertar(
        ReservaCota reserva, long tokensEntrada, long tokensSaida, String modelo) {
      acertos++;
      ultimaEntrada = tokensEntrada;
      ultimaSaida = tokensSaida;
      ultimoModelo = modelo;
      return 1;
    }

    @Override
    public synchronized void estornar(ReservaCota reserva) {
      estornos++;
    }

    @Override
    public UsoCota consultar(UUID produtorId) {
      return new UsoCota("padrao", true, 1000, 0, 1000, java.time.OffsetDateTime.now());
    }
  }

  /** A vaga volta no finally da thread, logo depois da auditoria: espera esse instante. */
  private AssistenteChatResponse perguntarQuandoHouverVaga(String pergunta) {
    long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (true) {
      try {
        return service.conversar(new AssistenteChatRequest(pergunta));
      } catch (AssistenteOcupadoException e) {
        if (System.nanoTime() > limite) {
          throw e;
        }
        Thread.onSpinWait();
      }
    }
  }
}
