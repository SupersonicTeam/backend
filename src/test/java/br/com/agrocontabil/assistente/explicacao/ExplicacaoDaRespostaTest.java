package br.com.agrocontabil.assistente.explicacao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.assistente.ContextoAssistente;
import br.com.agrocontabil.assistente.PerguntaInterrompidaException;
import br.com.agrocontabil.assistente.auditoria.AuditoriaAssistente;
import br.com.agrocontabil.assistente.llm.DisponibilidadeModelo;
import br.com.agrocontabil.assistente.llm.ModeloLinguagem;
import br.com.agrocontabil.assistente.orquestracao.Intencao;
import br.com.agrocontabil.assistente.orquestracao.ResultadoOrquestracao;
import br.com.agrocontabil.assistente.porta.CotaUsoPorta;
import br.com.agrocontabil.assistente.porta.CotaUsoPorta.ReservaCota;
import br.com.agrocontabil.assistente.rag.CitacaoLegal;
import br.com.agrocontabil.config.AssistenteProperties;
import br.com.agrocontabil.exception.AssistenteIndisponivelException;
import br.com.agrocontabil.model.DesfechoExplicacao;
import br.com.agrocontabil.model.DesfechoInteracao;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * As três fases da explicação (#268): quando ela se aplica, o que acontece sem tempo, sem modelo
 * ou sem saldo (a resposta sai sozinha e nunca há 429), a cota pelo uso real e o desfecho na
 * auditoria, sem o texto.
 */
@ExtendWith(MockitoExtension.class)
class ExplicacaoDaRespostaTest {

  private static final String MODELO = "anthropic/claude-haiku-5-5";
  private static final String TEXTO = "Você não tem guias em atraso até 12/11/2026.";
  private static final String EXPLICADA = "Isso quer dizer que está tudo em dia. Nenhuma cobrança"
      + " passou do vencimento.";

  @Mock private ExplicadorResposta explicador;
  @Mock private CotaUsoPorta cota;
  @Mock private AuditoriaAssistente auditoria;
  @Mock private ModeloLinguagem modelo;

  private final UUID interacaoId = UUID.randomUUID();
  private final DisponibilidadeModelo disponibilidade =
      new DisponibilidadeModelo(Duration.ofMinutes(1), System::nanoTime);
  private ExplicacaoDaResposta etapa;
  private ContextoAssistente contexto;
  private ReservaCota reserva;

  @BeforeEach
  void setUp() {
    AssistenteProperties propriedades = new AssistenteProperties();
    propriedades.getExplicacao().setEnabled(true);
    etapa = new ExplicacaoDaResposta(explicador, cota, auditoria, modelo, disponibilidade,
        propriedades);
    contexto = new ContextoAssistente(UUID.randomUUID(), LocalDate.of(2026, 11, 12));
    reserva = new ReservaCota(contexto.produtorId(), LocalDate.of(2026, 11, 9), 9_216, true);
  }

  private static ResultadoOrquestracao dados() {
    return new ResultadoOrquestracao(Intencao.DADOS, TEXTO, DesfechoInteracao.RESPONDIDA,
        List.of(), true);
  }

  private ExplicacaoDaResposta.Pedido pedido() {
    when(modelo.identificacao()).thenReturn(MODELO);
    when(cota.reservarExplicacao(contexto.produtorId(), MODELO)).thenReturn(Optional.of(reserva));
    return etapa.preparar(dados(), contexto, interacaoId, Duration.ofSeconds(5)).orElseThrow();
  }

  @Test
  @DisplayName("Só se aplica à resposta de dados aceita, sem lei, fora do fallback e com modelo")
  void seAplica() {
    assertThat(etapa.seAplica(dados(), contexto)).isTrue();
    assertThat(ExplicacaoDaResposta.desligada().seAplica(dados(), contexto)).isFalse();
    assertThat(etapa.seAplica(new ResultadoOrquestracao(Intencao.LEGISLACAO, TEXTO,
        DesfechoInteracao.RESPONDIDA, List.of(), false), contexto)).isFalse();
    assertThat(etapa.seAplica(new ResultadoOrquestracao(Intencao.MISTO, TEXTO,
        DesfechoInteracao.RESPONDIDA, List.of(), true), contexto)).isFalse();
    // Resposta de dados descartada (numero sem fonte, sem funcao): sem fontes, sem explicacao.
    assertThat(etapa.seAplica(new ResultadoOrquestracao(Intencao.DADOS, TEXTO,
        DesfechoInteracao.NUMERO_SEM_FONTE, List.of(), false), contexto)).isFalse();
    assertThat(etapa.seAplica(new ResultadoOrquestracao(Intencao.DADOS, TEXTO,
        DesfechoInteracao.MODO_FALLBACK, List.of(), true, true, List.of()), contexto)).isFalse();
    // Tratamento do produto ou retencao: texto legal do backend, com citacao.
    assertThat(etapa.seAplica(new ResultadoOrquestracao(Intencao.DADOS, TEXTO,
        DesfechoInteracao.RESPONDIDA, List.of(new CitacaoLegal("LC 214/2025", "art. 137", "u",
            "t")), true), contexto)).isFalse();
    assertThat(etapa.seAplica(new ResultadoOrquestracao(Intencao.DADOS, " ",
        DesfechoInteracao.RESPONDIDA, List.of(), true), contexto)).isFalse();
    assertThat(etapa.seAplica(null, contexto)).isFalse();
  }

  @Test
  @DisplayName("Atalho da tela (sem reserva para o modelo) e pergunta interrompida: não se aplica")
  void semModeloOuInterrompida() {
    ContextoAssistente atalho = new ContextoAssistente(UUID.randomUUID(), LocalDate.now());
    atalho.semReservaParaOModelo();
    assertThat(etapa.seAplica(dados(), atalho)).isFalse();

    contexto.interromper(DesfechoInteracao.CANCELADA);
    assertThat(etapa.seAplica(dados(), contexto)).isFalse();
  }

  @Test
  @DisplayName("Não se aplica: nada é reservado nem registrado")
  void naoSeAplica() {
    assertThat(etapa.preparar(new ResultadoOrquestracao(Intencao.LEGISLACAO, TEXTO,
        DesfechoInteracao.RESPONDIDA, List.of(), false), contexto, interacaoId,
        Duration.ofSeconds(5))).isEmpty();
    verifyNoInteractions(cota, auditoria, explicador);
  }

  @Test
  @DisplayName("Sem o registro da pergunta na auditoria: não acontece (RF020)")
  void semRegistro() {
    assertThat(etapa.preparar(dados(), contexto, null, Duration.ofSeconds(5))).isEmpty();
    verifyNoInteractions(cota, auditoria, explicador);
  }

  @Test
  @DisplayName("Sem tempo: TEMPO_ESGOTADO na auditoria, sem reserva")
  void semTempo() {
    assertThat(etapa.preparar(dados(), contexto, interacaoId, Duration.ZERO)).isEmpty();

    verify(auditoria).registrarExplicacao(eq(interacaoId),
        eq(DesfechoExplicacao.EXPLICACAO_DESCARTADA_TEMPO_ESGOTADO), anyLong(), isNull(),
        isNull());
    verifyNoInteractions(cota);
  }

  @Test
  @DisplayName("Disjuntor aberto (#146): MODELO_INDISPONIVEL, sem reserva nem chamada")
  void disjuntorAberto() {
    disponibilidade.registrarFalha();

    assertThat(etapa.preparar(dados(), contexto, interacaoId, Duration.ofSeconds(5))).isEmpty();

    verify(auditoria).registrarExplicacao(eq(interacaoId),
        eq(DesfechoExplicacao.EXPLICACAO_DESCARTADA_MODELO_INDISPONIVEL), anyLong(), isNull(),
        isNull());
    verifyNoInteractions(cota, explicador);
  }

  @Test
  @DisplayName("Sem saldo: SEM_SALDO na auditoria, sem chamada ao modelo e sem 429")
  void semSaldo() {
    when(modelo.identificacao()).thenReturn(MODELO);
    when(cota.reservarExplicacao(contexto.produtorId(), MODELO)).thenReturn(Optional.empty());

    assertThat(etapa.preparar(dados(), contexto, interacaoId, Duration.ofSeconds(5))).isEmpty();

    verify(auditoria).registrarExplicacao(eq(interacaoId),
        eq(DesfechoExplicacao.EXPLICACAO_DESCARTADA_SEM_SALDO), anyLong(), isNull(), isNull());
    verifyNoInteractions(explicador);
  }

  @Test
  @DisplayName("Falha ao reservar: ERRO na auditoria, a resposta não muda")
  void falhaAoReservar() {
    when(modelo.identificacao()).thenReturn(MODELO);
    when(cota.reservarExplicacao(contexto.produtorId(), MODELO))
        .thenThrow(new IllegalStateException("banco fora"));

    assertThat(etapa.preparar(dados(), contexto, interacaoId, Duration.ofSeconds(5))).isEmpty();

    verify(auditoria).registrarExplicacao(eq(interacaoId),
        eq(DesfechoExplicacao.EXPLICACAO_DESCARTADA_ERRO), anyLong(), isNull(), isNull());
  }

  @Test
  @DisplayName("Aceita: cota acertada só com os tokens da explicação, ACEITA na auditoria")
  void aceita() {
    // Uso da pergunta, ja acertado na reserva dela.
    contexto.registrarUso(new Prompt("pergunta"), comUso(500, 50));
    ExplicacaoDaResposta.Pedido pedido = pedido();
    when(explicador.explicar(TEXTO, contexto)).thenAnswer(inv -> {
      contexto.registrarUso(new Prompt("explicacao"), comUso(120, 30));
      return new ExplicadorResposta.Explicacao(EXPLICADA, DesfechoExplicacao.EXPLICACAO_ACEITA);
    });

    pedido.chamar();
    Optional<String> texto = etapa.concluir(pedido, false);

    assertThat(texto).contains(EXPLICADA);
    verify(cota).acertarExplicacao(reserva, 120, 30, MODELO);
    verify(cota, never()).estornar(any());
    verify(auditoria).registrarExplicacao(eq(interacaoId),
        eq(DesfechoExplicacao.EXPLICACAO_ACEITA), anyLong(), eq(120), eq(30));
  }

  @Test
  @DisplayName("Descartada pelo verificador: o uso é cobrado (o modelo foi chamado), sem texto")
  void descartada() {
    ExplicacaoDaResposta.Pedido pedido = pedido();
    when(explicador.explicar(TEXTO, contexto)).thenAnswer(inv -> {
      contexto.registrarUso(new Prompt("explicacao"), comUso(120, 30));
      return new ExplicadorResposta.Explicacao(null,
          DesfechoExplicacao.EXPLICACAO_DESCARTADA_NUMERO);
    });

    pedido.chamar();

    assertThat(etapa.concluir(pedido, false)).isEmpty();
    verify(cota).acertarExplicacao(reserva, 120, 30, MODELO);
    verify(auditoria).registrarExplicacao(eq(interacaoId),
        eq(DesfechoExplicacao.EXPLICACAO_DESCARTADA_NUMERO), anyLong(), eq(120), eq(30));
  }

  @Test
  @DisplayName("Modelo falhou: MODELO_INDISPONIVEL, reserva estornada")
  void modeloFalhou() {
    ExplicacaoDaResposta.Pedido pedido = pedido();
    when(explicador.explicar(TEXTO, contexto))
        .thenThrow(new AssistenteIndisponivelException("fora", null));

    pedido.chamar();

    assertThat(etapa.concluir(pedido, false)).isEmpty();
    verify(cota).estornar(reserva);
    verify(cota, never()).acertarExplicacao(any(), anyLong(), anyLong(), any());
    verify(auditoria).registrarExplicacao(eq(interacaoId),
        eq(DesfechoExplicacao.EXPLICACAO_DESCARTADA_MODELO_INDISPONIVEL), anyLong(), isNull(),
        isNull());
  }

  @Test
  @DisplayName("Cliente saiu no meio: CANCELADA, reserva estornada")
  void clienteSaiu() {
    ExplicacaoDaResposta.Pedido pedido = pedido();
    when(explicador.explicar(TEXTO, contexto)).thenAnswer(inv -> {
      contexto.interromper(DesfechoInteracao.CANCELADA);
      throw new PerguntaInterrompidaException();
    });

    pedido.chamar();

    assertThat(etapa.concluir(pedido, false)).isEmpty();
    verify(cota).estornar(reserva);
    verify(auditoria).registrarExplicacao(eq(interacaoId),
        eq(DesfechoExplicacao.EXPLICACAO_DESCARTADA_CANCELADA), anyLong(), isNull(), isNull());
  }

  @Test
  @DisplayName("Falha inesperada: ERRO, reserva estornada, nada lança")
  void falhaInesperada() {
    ExplicacaoDaResposta.Pedido pedido = pedido();
    when(explicador.explicar(TEXTO, contexto)).thenThrow(new IllegalStateException("bug"));

    pedido.chamar();

    assertThat(etapa.concluir(pedido, false)).isEmpty();
    verify(cota).estornar(reserva);
    verify(auditoria).registrarExplicacao(eq(interacaoId),
        eq(DesfechoExplicacao.EXPLICACAO_DESCARTADA_ERRO), anyLong(), isNull(), isNull());
  }

  @Test
  @DisplayName("Prazo vencido: TEMPO_ESGOTADO mesmo que a chamada tenha terminado logo depois")
  void prazoVencido() {
    ExplicacaoDaResposta.Pedido pedido = pedido();
    when(explicador.explicar(TEXTO, contexto)).thenReturn(
        new ExplicadorResposta.Explicacao(EXPLICADA, DesfechoExplicacao.EXPLICACAO_ACEITA));

    pedido.chamar();

    assertThat(etapa.concluir(pedido, true)).isEmpty();
    // A chamada terminou: o modelo foi usado e o uso e cobrado.
    verify(cota).acertarExplicacao(eq(reserva), anyLong(), anyLong(), eq(MODELO));
    verify(auditoria).registrarExplicacao(eq(interacaoId),
        eq(DesfechoExplicacao.EXPLICACAO_DESCARTADA_TEMPO_ESGOTADO), anyLong(), any(), any());
  }

  @Test
  @DisplayName("Prazo da pergunta vencido no meio: TEMPO_ESGOTADO")
  void prazoDaPergunta() {
    ExplicacaoDaResposta.Pedido pedido = pedido();
    when(explicador.explicar(TEXTO, contexto)).thenAnswer(inv -> {
      contexto.interromper(DesfechoInteracao.TEMPO_ESGOTADO);
      throw new AssistenteIndisponivelException("interrompida", null);
    });

    pedido.chamar();

    assertThat(etapa.concluir(pedido, false)).isEmpty();
    verify(auditoria).registrarExplicacao(eq(interacaoId),
        eq(DesfechoExplicacao.EXPLICACAO_DESCARTADA_TEMPO_ESGOTADO), anyLong(), isNull(),
        isNull());
  }

  @Test
  @DisplayName("Auditoria não gravada: a explicação aceita não é entregue (RF020)")
  void auditoriaFalhou() {
    ExplicacaoDaResposta.Pedido pedido = pedido();
    when(explicador.explicar(TEXTO, contexto)).thenReturn(
        new ExplicadorResposta.Explicacao(EXPLICADA, DesfechoExplicacao.EXPLICACAO_ACEITA));
    doThrow(new IllegalStateException("banco fora")).when(auditoria).registrarExplicacao(
        any(), any(), anyLong(), any(), any());

    pedido.chamar();

    assertThat(etapa.concluir(pedido, false)).isEmpty();
  }

  @Test
  @DisplayName("Falha ao acertar a cota não impede a explicação aceita")
  void falhaNaCota() {
    ExplicacaoDaResposta.Pedido pedido = pedido();
    when(explicador.explicar(TEXTO, contexto)).thenReturn(
        new ExplicadorResposta.Explicacao(EXPLICADA, DesfechoExplicacao.EXPLICACAO_ACEITA));
    when(cota.acertarExplicacao(any(), anyLong(), anyLong(), any()))
        .thenThrow(new IllegalStateException("banco fora"));

    pedido.chamar();

    assertThat(etapa.concluir(pedido, false)).contains(EXPLICADA);
  }

  private static ChatResponse comUso(int entrada, int saida) {
    return new ChatResponse(List.of(new Generation(new AssistantMessage("x"))),
        ChatResponseMetadata.builder().usage(new DefaultUsage(entrada, saida)).build());
  }
}
