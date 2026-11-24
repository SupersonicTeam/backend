package br.com.agrocontabil.assistente.continuidade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.assistente.ContextoAssistente;
import br.com.agrocontabil.assistente.FontePergunta;
import br.com.agrocontabil.assistente.RotaLegislacao;
import br.com.agrocontabil.assistente.ValidadorNumeros;
import br.com.agrocontabil.assistente.VerificadorAfirmacaoDados;
import br.com.agrocontabil.assistente.continuidade.CargaTurno.Faixa;
import br.com.agrocontabil.assistente.llm.DisponibilidadeModelo;
import br.com.agrocontabil.assistente.llm.ModeloLinguagem;
import br.com.agrocontabil.assistente.orquestracao.Intencao;
import br.com.agrocontabil.assistente.orquestracao.TrechosDasCompras;
import br.com.agrocontabil.assistente.rag.BuscaLegislacaoService;
import br.com.agrocontabil.assistente.rag.BuscaTextualLegislacaoService;
import br.com.agrocontabil.assistente.rag.TrechoRecuperado;
import br.com.agrocontabil.assistente.rag.ValidadorCitacao;
import br.com.agrocontabil.config.AssistenteProperties;
import br.com.agrocontabil.model.DesfechoInteracao;
import br.com.agrocontabil.model.MotivoReconferenciaLei;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/**
 * A confirmação só sai com prova literal (#584, decisão de 2026-11-23): "nunca confirma o erro" é
 * propriedade do código. Cada frase da resposta anterior que cita trecho tem de ter, nos trechos
 * que cita e nas citações literais para eles, o número, o sentido e (sem número) a cobertura das
 * palavras. Sem prova, o apoio neutro; uma chamada só.
 */
@ExtendWith(MockitoExtension.class)
class ReconferenciaLeiConfirmacaoTest {

  private static final LocalDate HOJE = LocalDate.of(2026, 11, 23);
  private static final String REFERENCIA = "LC 214/2025, art. 137, § 1º";
  private static final String TEXTO_TRECHO = "Ficam reduzidas em 60% (sessenta por cento) as "
      + "alíquotas do IBS e da CBS incidentes sobre o fornecimento dos produtos agropecuários in "
      + "natura. (Redação dada pela Lei Complementar nº 227, de 2026)";
  private static final String CITACAO = "Ficam reduzidas em 60% (sessenta por cento) as "
      + "alíquotas do IBS";
  private static final String CITACAO_SEM_NUMERO = "alíquotas do IBS e da CBS incidentes sobre o "
      + "fornecimento dos produtos agropecuários";
  private static final TrechoRecuperado TRECHO = new TrechoRecuperado(null, "t137",
      "LC 214/2025", "art. 137, § 1º", "https://planalto", TEXTO_TRECHO, 0.9);
  /** O mesmo dispositivo, com um trecho que nega. */
  private static final TrechoRecuperado VEDADO = new TrechoRecuperado(null, "t137",
      "LC 214/2025", "art. 137, § 1º", "https://planalto",
      "Não se aplica a redução de 60% (sessenta por cento) às operações com produtos "
          + "industrializados.", 0.9);
  private static final String CITACAO_VEDADO = "Não se aplica a redução de 60% (sessenta por "
      + "cento) às operações";
  private static final TrechoRecuperado PRAZO = new TrechoRecuperado(null, "t138",
      "LC 214/2025", "art. 138", "https://planalto",
      "Fica diferido o pagamento em 60 dias contados da entrada do insumo.", 0.8);
  private static final String APOIO_NEUTRO = "Reli o texto da lei em que a resposta anterior se "
      + "baseia (" + REFERENCIA + "). Ele está abaixo, com o link: leia o dispositivo e confira "
      + "com a resposta. " + ReconferenciaLei.ENQUADRAMENTO;

  @Mock private ModeloLinguagem modelo;
  @Mock private BuscaLegislacaoService busca;
  @Mock private BuscaTextualLegislacaoService buscaTextual;
  @Mock private RotaLegislacao rotaLegislacao;
  @Mock private TrechosDasCompras trechosDasCompras;
  @Mock private DisponibilidadeModelo disponibilidade;

  private ContextoAssistente contexto;
  private ReconferenciaLei reconferencia;

  @BeforeEach
  void montar() {
    contexto = new ContextoAssistente(UUID.randomUUID(), HOJE);
    reconferencia = new ReconferenciaLei(modelo, busca, buscaTextual, new ValidadorCitacao(),
        new ValidadorNumeros(), new VerificadorAfirmacaoDados(), rotaLegislacao,
        trechosDasCompras, disponibilidade, new AssistenteProperties());
    lenient().when(disponibilidade.disponivel()).thenReturn(true);
    lenient().when(buscaTextual.buscarDispositivos(anyList(), eq(HOJE)))
        .thenReturn(List.of(TRECHO));
    lenient().when(busca.buscar(any(), eq(HOJE))).thenReturn(List.of(TRECHO));
  }

  private MotivoReconferenciaLei motivo() {
    return contexto.reconferenciaLei().motivo();
  }

  @Test
  @DisplayName("Resposta certa com prova de número, sentido e cobertura: confirma")
  void confirmaComProva() {
    when(modelo.chamar(any())).thenReturn(confirmo("T1", CITACAO));

    ReconferenciaLei.Resultado r = reconferencia.reconferir(
        parDeLei("Sim, a soja tem redução de 60% da alíquota"), contexto);

    assertThat(r.desfecho()).isEqualTo(DesfechoContinuidade.RECONFERENCIA_CONFIRMOU);
    assertThat(motivo()).isEqualTo(MotivoReconferenciaLei.CONFIRMOU);
    assertThat(r.rota().texto()).startsWith(ReconferenciaLei.CONFIRMA);
    verify(modelo, times(1)).chamar(any());
    verify(rotaLegislacao, never()).responder(any(), any(), any(FontePergunta.class));
  }

  @Test
  @DisplayName("Resposta sem número, afirmativa e coberta pelo trecho: confirma")
  void confirmaSemNumeroCoberta() {
    when(modelo.chamar(any())).thenReturn(confirmo("T1", CITACAO_SEM_NUMERO));

    reconferencia.reconferir(parDeLei("Ficam reduzidas as alíquotas do IBS e da CBS sobre os "
        + "produtos agropecuários in natura"), contexto);

    assertThat(motivo()).isEqualTo(MotivoReconferenciaLei.CONFIRMOU);
  }

  @Test
  @DisplayName("Errada, número da resposta fora do trecho: apoio neutro")
  void erradaPorNumeroNaoConfirma() {
    when(modelo.chamar(any())).thenReturn(confirmo("T1", CITACAO));

    ReconferenciaLei.Resultado r = reconferencia.reconferir(
        parDeLei("Sim, a soja tem redução de 40% da alíquota"), contexto);

    assertThat(motivo()).isEqualTo(MotivoReconferenciaLei.CONFIRMO_NUMERO_FATIA);
    assertThat(r.rota().texto()).isEqualTo(APOIO_NEUTRO).doesNotContain("se confirma");
    assertThat(r.rota().desfecho()).isEqualTo(DesfechoInteracao.RECONFERENCIA_DESCARTADA);
  }

  @Test
  @DisplayName("Número certo da resposta, mas fora da citação literal que o modelo trouxe: sem "
      + "prova, apoio neutro")
  void numeroForaDaCitacao() {
    when(modelo.chamar(any())).thenReturn(confirmo("T1", CITACAO_SEM_NUMERO));

    reconferencia.reconferir(parDeLei("Sim, a soja tem redução de 60% da alíquota"), contexto);

    assertThat(motivo()).isEqualTo(MotivoReconferenciaLei.CONFIRMO_NUMERO_CITACAO);
  }

  @Test
  @DisplayName("A prova do número é por frase, não pela união das citações: a segunda frase "
      + "sem o número na citação do trecho dela não passa")
  void numeroPorFraseNaoPelaUniao() {
    org.mockito.Mockito.doReturn(List.of(TRECHO, PRAZO)).when(buscaTextual)
        .buscarDispositivos(anyList(), eq(HOJE));
    String fatia = "A soja tem redução de 60% da alíquota (" + REFERENCIA + "). O prazo é de "
        + "60 dias (LC 214/2025, art. 138).";
    when(modelo.chamar(any())).thenReturn(resposta("{\"veredito\": \"CONFIRMO\", "
        + "\"fundamentos\": [{\"marca\": \"T1\", \"citacao\": \"" + CITACAO + "\"}, "
        + "{\"marca\": \"T2\", \"citacao\": \"dias contados da entrada do insumo\"}]}"));

    reconferencia.reconferir(parComFatia(fatia), contexto);

    // O 60 da segunda frase está na citação da primeira (a união passaria), mas não na do T2.
    assertThat(motivo()).isEqualTo(MotivoReconferenciaLei.CONFIRMO_NUMERO_CITACAO);
  }

  @Test
  @DisplayName("Errada pelo sentido ('Sim' onde o trecho nega): apoio neutro, nunca 'se confirma'")
  void erradaPeloSentidoNaoConfirma() {
    org.mockito.Mockito.doReturn(List.of(VEDADO)).when(buscaTextual)
        .buscarDispositivos(anyList(), eq(HOJE));
    when(modelo.chamar(any())).thenReturn(confirmo("T1", CITACAO_VEDADO));

    ReconferenciaLei.Resultado r = reconferencia.reconferir(
        parDeLei("Sim, a redução de 60% alcança os produtos industrializados"), contexto);

    assertThat(motivo()).isEqualTo(MotivoReconferenciaLei.CONFIRMO_SENTIDO);
    assertThat(r.rota().texto()).isEqualTo(APOIO_NEUTRO);
    assertThat(r.desfecho()).isEqualTo(DesfechoContinuidade.RECONFERENCIA_DESCARTADA_SENTIDO);
  }

  @Test
  @DisplayName("'Sim' com trecho que restringe em outro ponto do dispositivo: sem prova, apoio")
  void simComTrechoQueRestringe() {
    TrechoRecuperado comExcecao = new TrechoRecuperado(null, "t137", "LC 214/2025",
        "art. 137, § 1º", "https://planalto", TEXTO_TRECHO + " Exceto os produtos "
            + "industrializados.", 0.9);
    org.mockito.Mockito.doReturn(List.of(comExcecao)).when(buscaTextual)
        .buscarDispositivos(anyList(), eq(HOJE));
    when(modelo.chamar(any())).thenReturn(confirmo("T1", CITACAO));

    reconferencia.reconferir(parDeLei("Sim, a soja tem redução de 60% da alíquota"), contexto);

    assertThat(motivo()).isEqualTo(MotivoReconferenciaLei.CONFIRMO_SENTIDO);
  }

  @Test
  @DisplayName("'Não' errada (citação afirmativa) não confirma; 'Não' com citação que nega confirma")
  void naoSoComNegacao() {
    when(modelo.chamar(any())).thenReturn(confirmo("T1", CITACAO));
    reconferencia.reconferir(parDeLei("Não, a soja não tem redução de 60% da alíquota"), contexto);
    assertThat(motivo()).isEqualTo(MotivoReconferenciaLei.CONFIRMO_SENTIDO);

    contexto = new ContextoAssistente(UUID.randomUUID(), HOJE);
    org.mockito.Mockito.doReturn(List.of(VEDADO)).when(buscaTextual)
        .buscarDispositivos(anyList(), eq(HOJE));
    when(modelo.chamar(any())).thenReturn(confirmo("T1", CITACAO_VEDADO));
    reconferencia.reconferir(
        parDeLei("Não, a redução de 60% não se aplica aos produtos industrializados"), contexto);
    assertThat(motivo()).isEqualTo(MotivoReconferenciaLei.CONFIRMOU);
  }

  @Test
  @DisplayName("Resposta sem número que o trecho não cobre: apoio neutro (cobertura)")
  void erradaSemNumeroNaoConfirma() {
    when(modelo.chamar(any())).thenReturn(confirmo("T1", CITACAO_SEM_NUMERO));

    ReconferenciaLei.Resultado r = reconferencia.reconferir(
        parDeLei("O produtor deve recolher mensalmente a contribuição previdenciária "
            + "patronal sobre a folha de pagamento"), contexto);

    assertThat(motivo()).isEqualTo(MotivoReconferenciaLei.CONFIRMO_COBERTURA);
    assertThat(r.rota().texto()).isEqualTo(APOIO_NEUTRO);
    assertThat(r.desfecho()).isEqualTo(DesfechoContinuidade.RECONFERENCIA_DESCARTADA_COBERTURA);
  }

  @Test
  @DisplayName("Frase com número e sem trecho citado não tem prova")
  void numeroSemTrecho() {
    when(modelo.chamar(any())).thenReturn(confirmo("T1", CITACAO));
    String fatia = "A soja tem redução de 60% da alíquota (" + REFERENCIA + "). O prazo é de "
        + "60 dias.";

    reconferencia.reconferir(parComFatia(fatia), contexto);

    assertThat(motivo()).isEqualTo(MotivoReconferenciaLei.CONFIRMO_NUMERO_FATIA);
  }

  private ParConferido parDeLei(String frase) {
    return parComFatia(frase + " (" + REFERENCIA + ").");
  }

  private ParConferido parComFatia(String fatia) {
    String resposta = fatia + "\n\nO que isso quer dizer: IBS é o imposto.";
    return new ParConferido("a soja tem redução?", resposta, "v1.a.b",
        new CargaTurno(1L, Intencao.LEGISLACAO, false, null, null, null, false,
            new LeiAssinada(LeiAssinada.Classe.CONFIRMADA, new Faixa(0, fatia.length()), null,
                null, false, false, null)), 0, false);
  }

  private static ChatResponse confirmo(String marca, String citacao) {
    return resposta("{\"veredito\": \"CONFIRMO\", \"fundamentos\": [{\"marca\": \"" + marca
        + "\", \"citacao\": \"" + citacao + "\"}]}");
  }

  private static ChatResponse resposta(String texto) {
    return new ChatResponse(List.of(new Generation(new AssistantMessage(texto))));
  }
}
