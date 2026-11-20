package br.com.agrocontabil.assistente.continuidade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
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
 * Contestação de lei: por padrão (decisão do Murillo, 2026-11-20) o veredito {@code CORRIJO} do
 * modelo nunca vira texto de correção. A resposta é o apoio: texto fixo do backend e trecho
 * literal da lei, sem a segunda chamada (a concordância). A chave
 * {@code app.assistente.continuidade.lei-corrijo-por-texto} traz de volta a correção por texto.
 */
@ExtendWith(MockitoExtension.class)
class ReconferenciaLeiSoApoioTest {

  private static final LocalDate HOJE = LocalDate.of(2026, 11, 20);
  private static final String REFERENCIA = "LC 214/2025, art. 137, § 1º";
  private static final String TEXTO_TRECHO = "Ficam reduzidas em 60% (sessenta por cento) as "
      + "alíquotas do IBS e da CBS incidentes sobre o fornecimento dos produtos agropecuários in "
      + "natura. (Redação dada pela Lei Complementar nº 227, de 2026)";
  private static final String CITACAO = "Ficam reduzidas em 60% (sessenta por cento) as "
      + "alíquotas do IBS";
  private static final TrechoRecuperado TRECHO = new TrechoRecuperado(null, "t137",
      "LC 214/2025", "art. 137, § 1º", "https://planalto", TEXTO_TRECHO, 0.9);
  private static final TrechoRecuperado OUTRO = new TrechoRecuperado(null, "t138",
      "LC 214/2025", "art. 138", "u", "Outro trecho de lei sobre insumos e prazos.", 0.5);
  /** Apoio depois de o modelo ser chamado (frase aprovada pelo Murillo em 2026-11-20). */
  private static final String APOIO_ESPERADO = "Reli o texto da lei em que a resposta anterior "
      + "se baseia (" + REFERENCIA + "). Ele está abaixo, com o link: leia o dispositivo e "
      + "confira com a resposta. " + ReconferenciaLei.ENQUADRAMENTO;
  /** Apoio sem chamar o modelo: a abertura de sempre. */
  private static final String APOIO_SEM_MODELO = "A resposta anterior se baseia em " + REFERENCIA
      + ". O texto de cada dispositivo está abaixo, com o link. " + ReconferenciaLei.ENQUADRAMENTO;

  @Mock private ModeloLinguagem modelo;
  @Mock private BuscaLegislacaoService busca;
  @Mock private BuscaTextualLegislacaoService buscaTextual;
  @Mock private RotaLegislacao rotaLegislacao;
  @Mock private TrechosDasCompras trechosDasCompras;
  @Mock private DisponibilidadeModelo disponibilidade;

  private ContextoAssistente contexto;
  private ReconferenciaLei reconferencia;
  private AssistenteProperties propriedades;

  @BeforeEach
  void montar() {
    contexto = new ContextoAssistente(UUID.randomUUID(), HOJE);
    propriedades = new AssistenteProperties();
    reconferencia = new ReconferenciaLei(modelo, busca, buscaTextual, new ValidadorCitacao(),
        new ValidadorNumeros(), new VerificadorAfirmacaoDados(), rotaLegislacao,
        trechosDasCompras, disponibilidade, propriedades);
    lenient().when(disponibilidade.disponivel()).thenReturn(true);
    lenient().when(buscaTextual.buscarDispositivos(anyList(), eq(HOJE)))
        .thenReturn(List.of(TRECHO));
    lenient().when(busca.buscar(any(), eq(HOJE))).thenReturn(List.of(TRECHO, OUTRO));
  }

  @Test
  @DisplayName("Padrão: a chave da correção por texto vem desligada")
  void chaveDesligadaPorPadrao() {
    assertThat(new AssistenteProperties().getContinuidade().isLeiCorrijoPorTexto()).isFalse();
  }

  @Test
  @DisplayName("A chave se liga pela propriedade app.assistente.continuidade.lei-corrijo-por-texto")
  void chaveSeLigaPelaPropriedade() {
    java.util.Map<String, String> fonte = java.util.Map.of(
        "app.assistente.continuidade.lei-corrijo-por-texto", "true");
    AssistenteProperties ligada = new org.springframework.boot.context.properties.bind.Binder(
        new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(
            fonte))
        .bind("app.assistente", AssistenteProperties.class).get();

    assertThat(ligada.getContinuidade().isLeiCorrijoPorTexto()).isTrue();
  }

  @Test
  @DisplayName("CORRIJO do modelo: a resposta é o apoio, sem 'corrijo', sem a frase do modelo e "
      + "sem a segunda chamada")
  void corrijoViraApoio() {
    when(modelo.chamar(any())).thenReturn(resposta(correcao()));

    ReconferenciaLei.Resultado r = reconferencia.reconferir(
        parDeLei("A soja tem redução de 40% da alíquota"), contexto);

    assertThat(r.desfecho()).isEqualTo(DesfechoContinuidade.RECONFERENCIA_APOIO_SEM_CORRECAO);
    assertThat(r.modeloRespondeu()).isTrue();
    assertThat(r.rota().desfecho()).isEqualTo(DesfechoInteracao.RECONFERENCIA_DESCARTADA);
    assertThat(r.rota().texto()).isEqualTo(APOIO_ESPERADO)
        .doesNotContain("corrijo").doesNotContain("Onde eu disse").doesNotContain("60%");
    assertThat(r.rota().citacoes()).hasSize(1);
    assertThat(contexto.reconferenciaLei().motivo())
        .isEqualTo(MotivoReconferenciaLei.CORRIJO_INDICADO_APOIO);
    assertThat(contexto.reconferenciaLei().tokensEntradaConcordancia()).isNull();
    assertThat(contexto.registroTurno().reconferida()).isTrue();
    verify(modelo, times(1)).chamar(any());
    verify(rotaLegislacao, never()).responder(any(), any(), any(FontePergunta.class));
  }

  @Test
  @DisplayName("Duas aberturas de apoio: a nova, só quando o modelo foi chamado e respondeu, e a "
      + "de sempre nos caminhos sem modelo")
  void duasAberturas() {
    // Modelo respondeu (NAO_SUSTENTA, descartes e CORRIJO): a abertura nova, no singular.
    when(modelo.chamar(any())).thenReturn(resposta("{\"veredito\": \"NAO_SUSTENTA\"}"),
        resposta("texto solto"));
    assertThat(reconferencia.reconferir(parDeLei("A soja tem redução de 40% da alíquota"),
        contexto).rota().texto()).isEqualTo(APOIO_ESPERADO);
    assertThat(reconferencia.reconferir(parDeLei("A soja tem redução de 40% da alíquota"),
        new ContextoAssistente(UUID.randomUUID(), HOJE)).rota().texto())
        .isEqualTo(APOIO_ESPERADO);

    // Sem chamar o modelo (disjuntor, busca que falha): a abertura de sempre.
    when(disponibilidade.disponivel()).thenReturn(false);
    assertThat(reconferencia.reconferir(parDeLei("A soja tem redução de 40% da alíquota"),
        new ContextoAssistente(UUID.randomUUID(), HOJE)).rota().texto())
        .isEqualTo(APOIO_SEM_MODELO);
    when(disponibilidade.disponivel()).thenReturn(true);
    org.mockito.Mockito.doThrow(new br.com.agrocontabil.exception.AssistenteIndisponivelException(
        "fora", new RuntimeException("x"))).when(busca).buscar(any(), eq(HOJE));
    assertThat(reconferencia.reconferir(parDeLei("A soja tem redução de 40% da alíquota"),
        new ContextoAssistente(UUID.randomUUID(), HOJE)).rota().texto())
        .isEqualTo(APOIO_SEM_MODELO);
  }

  @Test
  @DisplayName("Mais de um dispositivo: a abertura nova no plural, com o mesmo sentido")
  void aberturaNovaNoPlural() {
    org.mockito.Mockito.doReturn(List.of(TRECHO, OUTRO)).when(buscaTextual)
        .buscarDispositivos(anyList(), eq(HOJE));
    String fatia = "A soja tem redução de 40% da alíquota (" + REFERENCIA
        + "; LC 214/2025, art. 138).";
    ParConferido par = new ParConferido("a soja tem redução?", fatia, "v1.a.b",
        new CargaTurno(1L, Intencao.LEGISLACAO, false, null, null, null, false,
            new LeiAssinada(LeiAssinada.Classe.CONFIRMADA, new Faixa(0, fatia.length()), null,
                null, false, false, null)), 0, false);
    when(modelo.chamar(any())).thenReturn(resposta("{\"veredito\": \"NAO_SUSTENTA\"}"));

    String texto = reconferencia.reconferir(par, contexto).rota().texto();

    assertThat(texto).isEqualTo("Reli o texto da lei nos dispositivos em que a resposta anterior "
        + "se baseia (" + REFERENCIA + " e LC 214/2025, art. 138). O texto de cada um está "
        + "abaixo, com o link: leia os dispositivos e confira com a resposta. "
        + ReconferenciaLei.ENQUADRAMENTO);
  }

  @Test
  @DisplayName("Apoio com o modelo e sem dispositivo na base: a frase de sempre, sem o link")
  void semDispositivoNaBase() {
    org.mockito.Mockito.doReturn(List.of()).when(buscaTextual)
        .buscarDispositivos(anyList(), eq(HOJE));

    // Sem trecho citado achado na base, a reconferencia nem chama o modelo: apoio sem dispositivo.
    String texto = reconferencia.reconferir(parDeLei("A soja tem redução de 40% da alíquota"),
        contexto).rota().texto();

    assertThat(texto).isEqualTo(ReconferenciaLei.APOIO_SEM_DISPOSITIVO + " "
        + ReconferenciaLei.ENQUADRAMENTO);
  }

  @Test
  @DisplayName("CORRIJO mal formado também sai como apoio, com o mesmo motivo")
  void corrijoMalFormado() {
    when(modelo.chamar(any())).thenReturn(
        resposta("{\"veredito\": \"CORRIJO\", \"correcoes\": []}"));

    ReconferenciaLei.Resultado r = reconferencia.reconferir(
        parDeLei("A soja tem redução de 40% da alíquota"), contexto);

    assertThat(r.rota().texto()).isEqualTo(APOIO_ESPERADO);
    assertThat(contexto.reconferenciaLei().motivo())
        .isEqualTo(MotivoReconferenciaLei.CORRIJO_INDICADO_APOIO);
  }

  @Test
  @DisplayName("MISTO: o CORRIJO também vira apoio e as compras são buscadas uma vez, sem a "
      + "concordância")
  void mistoSemConcordancia() {
    String fatia = "A soja tem redução de 40% da alíquota (" + REFERENCIA + ").";
    ParConferido misto = new ParConferido("a soja tem redução?", "Dados.\n\n" + fatia, "v1.a.b",
        new CargaTurno(1L, Intencao.MISTO, false, null, null, null, false,
            new LeiAssinada(LeiAssinada.Classe.CONFIRMADA, new Faixa(8, 8 + fatia.length()), null,
                "a soja tem redução?", false, false, null)), 0, false);
    when(trechosDasCompras.buscar(any())).thenReturn(List.of());
    when(modelo.chamar(any())).thenReturn(resposta(correcao()));

    ReconferenciaLei.Resultado r = reconferencia.reconferir(misto, contexto);

    assertThat(r.rota().texto()).isEqualTo(APOIO_ESPERADO);
    verify(trechosDasCompras, times(1)).buscar(any());
    verify(rotaLegislacao, never()).responderRegraGeral(any(), any(), any(), anyBoolean(),
        any());
  }

  @Test
  @DisplayName("A confirmação validada segue como está")
  void confirmacaoNaoMuda() {
    when(modelo.chamar(any())).thenReturn(resposta("{\"veredito\": \"CONFIRMO\", "
        + "\"fundamentos\": [{\"marca\": \"T1\", \"citacao\": \"" + CITACAO + "\"}]}"));

    ReconferenciaLei.Resultado r = reconferencia.reconferir(
        parDeLei("Sim, a soja tem redução de 60% da alíquota"), contexto);

    assertThat(r.desfecho()).isEqualTo(DesfechoContinuidade.RECONFERENCIA_CONFIRMOU);
    assertThat(contexto.reconferenciaLei().motivo()).isEqualTo(MotivoReconferenciaLei.CONFIRMOU);
  }

  @Test
  @DisplayName("Chave ligada: a correção por texto volta, com as guardas e a concordância")
  void chaveLigadaTrazACorrecaoDeVolta() {
    propriedades.getContinuidade().setLeiCorrijoPorTexto(true);
    when(modelo.chamar(any())).thenReturn(resposta(correcao()));
    TrechoRecuperado t1 = new TrechoRecuperado("T1", "t137", "LC 214/2025", "art. 137, § 1º",
        "u", TEXTO_TRECHO, 0.9);
    when(rotaLegislacao.responder(eq("a soja tem redução?"), any(), any(FontePergunta.class)))
        .thenReturn(new br.com.agrocontabil.assistente.ResultadoRota("texto",
            DesfechoInteracao.RESPONDIDA, List.of(), List.of(),
            new br.com.agrocontabil.assistente.DetalheLei("texto",
                "A soja tem redução de 60% da alíquota [T1].", List.of(t1))));

    ReconferenciaLei.Resultado r = reconferencia.reconferir(
        parDeLei("A soja tem redução de 40% da alíquota"), contexto);

    assertThat(r.desfecho()).isEqualTo(DesfechoContinuidade.RECONFERENCIA_CORRIGIU);
    assertThat(r.rota().texto()).startsWith(ReconferenciaLei.CORRIJO_UM);
    assertThat(contexto.reconferenciaLei().motivo()).isEqualTo(MotivoReconferenciaLei.CORRIGIU);
  }

  private ParConferido parDeLei(String frase) {
    String fatia = frase + " (" + REFERENCIA + ").";
    String resposta = fatia + "\n\nO que isso quer dizer: IBS é o imposto.";
    return new ParConferido("a soja tem redução?", resposta, "v1.a.b",
        new CargaTurno(1L, Intencao.LEGISLACAO, false, null, null, null, false,
            new LeiAssinada(LeiAssinada.Classe.CONFIRMADA, new Faixa(0, fatia.length()), null,
                null, false, false, null)), 0, false);
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
