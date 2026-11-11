package br.com.agrocontabil.assistente.continuidade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.assistente.ContextoAssistente;
import br.com.agrocontabil.assistente.DetalheLei;
import br.com.agrocontabil.assistente.FontePergunta;
import br.com.agrocontabil.assistente.ResultadoRota;
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
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * Reconferência de lei (#499): textos fixos sem modelo, CONFIRMO e CORRIJO só com as
 * conferências, apoio sem recuar e nunca "Não consegui confirmar" nem "sem fundamento".
 */
@ExtendWith(MockitoExtension.class)
class ReconferenciaLeiTest {

  private static final LocalDate HOJE = LocalDate.of(2026, 11, 11);
  private static final String REFERENCIA = "LC 214/2025, art. 137, § 1º";
  private static final String TEXTO_TRECHO = "Ficam reduzidas em 60% (sessenta por cento) as "
      + "alíquotas do IBS e da CBS incidentes sobre o fornecimento dos produtos agropecuários in "
      + "natura. (Redação dada pela Lei Complementar nº 227, de 2026)";
  private static final String CITACAO = "Ficam reduzidas em 60% (sessenta por cento) as "
      + "alíquotas do IBS";
  private static final TrechoRecuperado TRECHO = new TrechoRecuperado(null, "t137",
      "LC 214/2025", "art. 137, § 1º", "https://planalto", TEXTO_TRECHO, 0.9);

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

  @Test
  @DisplayName("Lei certa: uma chamada, sem a dúvida do produtor no prompt, e se confirma")
  void confirma() {
    when(modelo.chamar(any())).thenReturn(resposta("{\"veredito\": \"CONFIRMO\", "
        + "\"fundamentos\": [{\"marca\": \"T1\", \"citacao\": \"" + CITACAO + "\"}]}"));

    ReconferenciaLei.Resultado r = reconferencia.reconferir(
        parDeLei("Sim, a soja tem redução de 60% da alíquota"), contexto);

    ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
    verify(modelo).chamar(prompt.capture());
    String usuario = prompt.getValue().getInstructions().get(1).getText();
    assertThat(usuario).contains("[T1]").doesNotContain("(LC 214/2025")
        .doesNotContain("tem certeza");
    assertThat(r.desfecho()).isEqualTo(DesfechoContinuidade.RECONFERENCIA_CONFIRMOU);
    assertThat(r.rota().texto()).startsWith(ReconferenciaLei.CONFIRMA)
        .contains("O que diz a lei (" + REFERENCIA + "): “" + CITACAO + "”")
        .contains(ReconferenciaLei.ENQUADRAMENTO);
    assertThat(r.rota().desfecho()).isEqualTo(DesfechoInteracao.RESPONDIDA);
    assertThat(contexto.registroTurno().reconferida()).isTrue();
  }

  @Test
  @DisplayName("Premissa falsa ('Sim, 40%' com o trecho dizendo 60): nunca 'se confirma'")
  void naoConfirmaNumeroSemApoio() {
    when(modelo.chamar(any())).thenReturn(resposta("{\"veredito\": \"CONFIRMO\", "
        + "\"fundamentos\": [{\"marca\": \"T1\", \"citacao\": \"" + CITACAO + "\"}]}"));

    ReconferenciaLei.Resultado r = reconferencia.reconferir(
        parDeLei("Sim, a soja tem redução de 40% da alíquota"), contexto);

    assertThat(r.desfecho()).isEqualTo(DesfechoContinuidade.RECONFERENCIA_DESCARTADA_NUMERO);
    assertThat(r.rota().texto()).doesNotContain(ReconferenciaLei.CONFIRMA)
        .startsWith("A resposta anterior se baseia em " + REFERENCIA);
    assertThat(r.rota().desfecho()).isEqualTo(DesfechoInteracao.RECONFERENCIA_DESCARTADA);
  }

  @Test
  @DisplayName("Citação tirada da anotação entre parênteses não vale")
  void citacaoNaAnotacao() {
    when(modelo.chamar(any())).thenReturn(resposta("{\"veredito\": \"CONFIRMO\", "
        + "\"fundamentos\": [{\"marca\": \"T1\", \"citacao\": \"Redação dada pela Lei "
        + "Complementar nº 227\"}]}"));

    assertThat(reconferencia.reconferir(parDeLei("Sim, a soja tem redução de 60% da alíquota"),
        contexto).desfecho()).isEqualTo(DesfechoContinuidade.RECONFERENCIA_DESCARTADA_CITACAO);
  }

  @Test
  @DisplayName("Percentual trocado: corrijo um ponto, com o pedaço original e a citação")
  void corrige() {
    when(modelo.chamar(any())).thenReturn(resposta(correcao()));
    concordanciaCom("A soja tem redução de 60% da alíquota [T1].");

    ReconferenciaLei.Resultado r = reconferencia.reconferir(
        parDeLei("A soja tem redução de 40% da alíquota"), contexto);

    assertThat(r.desfecho()).isEqualTo(DesfechoContinuidade.RECONFERENCIA_CORRIGIU);
    assertThat(r.rota().texto()).startsWith(ReconferenciaLei.CORRIJO_UM)
        .contains("Onde eu disse “A soja tem redução de 40% da alíquota”")
        .contains("o certo é: A soja tem redução de 60% da alíquota (" + REFERENCIA + ")")
        .contains("“" + CITACAO + "”");
  }

  @Test
  @DisplayName("Sem concordância da rota de lei normal, nada é corrigido: apoio")
  void semConcordancia() {
    when(modelo.chamar(any())).thenReturn(resposta(correcao()));
    concordanciaCom("A soja tem redução de 40% da alíquota [T1].");

    ReconferenciaLei.Resultado r = reconferencia.reconferir(
        parDeLei("A soja tem redução de 40% da alíquota"), contexto);

    assertThat(r.desfecho())
        .isEqualTo(DesfechoContinuidade.RECONFERENCIA_DESCARTADA_CONCORDANCIA);
    assertThat(r.rota().texto()).doesNotContain("corrijo");
  }

  @Test
  @DisplayName("NAO_SUSTENTA e saída fora de JSON: apoio, nunca 'Não consegui confirmar'")
  void naoSustenta() {
    when(modelo.chamar(any())).thenReturn(resposta("{\"veredito\": \"NAO_SUSTENTA\"}"),
        resposta("texto solto"));

    for (int i = 0; i < 2; i++) {
      ReconferenciaLei.Resultado r = reconferencia.reconferir(
          parDeLei("Sim, a soja tem redução de 60% da alíquota"), contexto);
      assertThat(r.rota().texto()).doesNotContain(RotaLegislacao.TRECHOS_SEM_CONFIRMACAO)
          .doesNotContain("Não encontrei fundamento legal")
          .startsWith("A resposta anterior se baseia em");
      assertThat(r.modeloRespondeu()).isTrue();
    }
  }

  @Test
  @DisplayName("Textos fixos sem modelo: atalho, sem confirmação, já reconferida e disjuntor aberto")
  void textosFixos() {
    for (LeiAssinada lei : List.of(
        new LeiAssinada(LeiAssinada.Classe.CONFIRMADA, null, List.of(), null, false, false,
            "REFORMA"),
        new LeiAssinada(LeiAssinada.Classe.SEM_CONFIRMACAO, null,
            List.of("LC 214/2025|art. 137, § 1º"), null, false, false, null),
        new LeiAssinada(LeiAssinada.Classe.SEM_FUNDAMENTO, null, List.of(), null, false, false,
            null))) {
      ContextoAssistente outro = new ContextoAssistente(UUID.randomUUID(), HOJE);
      ParConferido par = new ParConferido("p", "resposta anterior", "v1.a.b",
          new CargaTurno(1L, Intencao.LEGISLACAO, false, null, null, null, false, lei), 0, false);

      ReconferenciaLei.Resultado r = reconferencia.reconferir(par, outro);

      assertThat(r.modeloRespondeu()).isFalse();
      assertThat(r.rota().texto()).endsWith(ReconferenciaLei.ENQUADRAMENTO);
      assertThat(outro.registroTurno().turnoDeOrigem().assinatura()).isEqualTo("v1.a.b");
    }
    when(disponibilidade.disponivel()).thenReturn(false);
    assertThat(reconferencia.reconferir(parDeLei("Sim, a soja tem redução de 60% da alíquota"),
        contexto).desfecho()).isEqualTo(DesfechoContinuidade.RECONFERENCIA_SEM_MODELO);
    verify(modelo, never()).chamar(any());
  }

  @Test
  @DisplayName("Utilitários: grupos de referência, marcas e âncora")
  void utilitarios() {
    assertThat(ReconferenciaLei.referenciasDoGrupo("LC 214/2025, art. 137; EC 132/2023, art. 1"))
        .containsExactly("LC 214/2025|art. 137", "EC 132/2023|art. 1");
    assertThat(ReconferenciaLei.referenciasDoGrupo("sessenta por cento")).isEmpty();
    assertThat(ReconferenciaLei.ancora("a soja tem redução de 40% [T1]",
        "A soja tem redução de 40% (LC 214/2025, art. 137).")).contains("A soja tem redução de 40");
    assertThat(ReconferenciaLei.polaridade("Não, a soja paga")).isEqualTo("NAO");
    assertThat(ReconferenciaLei.polaridade("Sim. Tem redução")).isEqualTo("SIM");
  }

  private ParConferido parDeLei(String frase) {
    String fatia = frase + " (" + REFERENCIA + ").";
    String resposta = fatia + "\n\nO que isso quer dizer: IBS é o imposto.";
    return new ParConferido("a soja tem redução?", resposta, "v1.a.b",
        new CargaTurno(1L, Intencao.LEGISLACAO, false, null, null, null, false,
            new LeiAssinada(LeiAssinada.Classe.CONFIRMADA, new Faixa(0, fatia.length()), null,
                null, false, false, null)), 0, false);
  }

  private void concordanciaCom(String textoComMarcas) {
    TrechoRecuperado t1 = new TrechoRecuperado("T1", "t137", "LC 214/2025", "art. 137, § 1º",
        "u", TEXTO_TRECHO, 0.9);
    when(rotaLegislacao.responder(eq("a soja tem redução?"), any(), any(FontePergunta.class)))
        .thenReturn(new ResultadoRota("texto", DesfechoInteracao.RESPONDIDA, List.of(), List.of(),
            new DetalheLei("texto", textoComMarcas, List.of(t1))));
  }

  private static String correcao() {
    return "{\"veredito\": \"CORRIJO\", \"correcoes\": [{\"fraseAnterior\": \"A soja tem "
        + "redução de 40% da alíquota [T1]\", \"fraseCorrigida\": \"A soja tem redução de 60% da "
        + "alíquota [T1]\", \"fundamento\": {\"marca\": \"T1\", \"citacao\": \"" + CITACAO
        + "\"}}]}";
  }

  private static ChatResponse resposta(String texto) {
    return new ChatResponse(List.of(new Generation(new AssistantMessage(texto))));
  }
}
