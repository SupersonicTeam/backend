package br.com.agrocontabil.assistente.continuidade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
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
import br.com.agrocontabil.assistente.porta.AuditoriaPorta.RegistroReconferenciaLei;
import br.com.agrocontabil.assistente.rag.BuscaLegislacaoService;
import br.com.agrocontabil.assistente.rag.BuscaTextualLegislacaoService;
import br.com.agrocontabil.assistente.rag.TrechoRecuperado;
import br.com.agrocontabil.assistente.rag.ValidadorCitacao;
import br.com.agrocontabil.config.AssistenteProperties;
import br.com.agrocontabil.exception.AssistenteIndisponivelException;
import br.com.agrocontabil.model.BuscaReconferenciaLei;
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
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * Instrumentação da reconferência de lei (#584): cada desfecho grava o motivo (código de lista
 * fechada) e o que foi ao prompt, e a instrumentação não muda a resposta nem o desfecho.
 */
@ExtendWith(MockitoExtension.class)
class ReconferenciaLeiAuditoriaTest {

  private static final LocalDate HOJE = LocalDate.of(2026, 11, 11);
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
        trechosDasCompras, disponibilidade, comCorrecaoPorTexto());
    lenient().when(disponibilidade.disponivel()).thenReturn(true);
    lenient().when(buscaTextual.buscarDispositivos(anyList(), eq(HOJE)))
        .thenReturn(List.of(TRECHO));
    lenient().when(busca.buscar(any(), eq(HOJE))).thenReturn(List.of(TRECHO, OUTRO));
  }

  private MotivoReconferenciaLei motivo() {
    return contexto.reconferenciaLei().motivo();
  }

  /** Os testes desta classe cobrem a correção por texto e suas guardas (#584: chave ligada). */
  private static AssistenteProperties comCorrecaoPorTexto() {
    AssistenteProperties propriedades = new AssistenteProperties();
    propriedades.getContinuidade().setLeiCorrijoPorTexto(true);
    return propriedades;
  }

  @Test
  @DisplayName("CONFIRMOU: o motivo e o que foi ao prompt (2 trechos, 1 citado, busca OK)")
  void confirmou() {
    when(modelo.chamar(any())).thenReturn(resposta("{\"veredito\": \"CONFIRMO\", "
        + "\"fundamentos\": [{\"marca\": \"T1\", \"citacao\": \"" + CITACAO + "\"}]}"));

    ReconferenciaLei.Resultado r = reconferencia.reconferir(
        parDeLei("Sim, a soja tem redução de 60% da alíquota"), contexto);

    assertThat(r.desfecho()).isEqualTo(DesfechoContinuidade.RECONFERENCIA_CONFIRMOU);
    RegistroReconferenciaLei registro = contexto.reconferenciaLei();
    assertThat(registro.motivo()).isEqualTo(MotivoReconferenciaLei.CONFIRMOU);
    assertThat(registro.dispositivos()).isEqualTo(1);
    assertThat(registro.trechosCitadosAchados()).isEqualTo(1);
    assertThat(registro.busca()).isEqualTo(BuscaReconferenciaLei.OK);
    assertThat(registro.trechosBusca()).isEqualTo(2);
    assertThat(registro.trechosCompras()).isNull();
    assertThat(registro.trechosPrompt()).isEqualTo(2);
    assertThat(registro.trechosPromptCitados()).isEqualTo(1);
    assertThat(registro.caracteresTrechos())
        .isEqualTo(TEXTO_TRECHO.length() + OUTRO.texto().length());
    assertThat(registro.tokensEntradaConcordancia()).isNull();
    assertThat(registro.desfechoConcordancia()).isNull();
  }

  @Test
  @DisplayName("CORRIGIU: a concordância grava os tokens da chamada e o desfecho da rota normal")
  void corrigiu() {
    when(modelo.chamar(any())).thenReturn(resposta(correcao()));
    concordanciaCom("A soja tem redução de 60% da alíquota [T1].", 1100);

    ReconferenciaLei.Resultado r = reconferencia.reconferir(
        parDeLei("A soja tem redução de 40% da alíquota"), contexto);

    assertThat(r.desfecho()).isEqualTo(DesfechoContinuidade.RECONFERENCIA_CORRIGIU);
    assertThat(motivo()).isEqualTo(MotivoReconferenciaLei.CORRIGIU);
    assertThat(contexto.reconferenciaLei().tokensEntradaConcordancia()).isEqualTo(1100);
    assertThat(contexto.reconferenciaLei().desfechoConcordancia())
        .isEqualTo(DesfechoInteracao.RESPONDIDA);
  }

  @Test
  @DisplayName("Concordância que não vale: um motivo para cada ramo da guarda (h)")
  void concordancias() {
    when(modelo.chamar(any())).thenReturn(resposta(correcao()));
    String fatia = "A soja tem redução de 40% da alíquota";

    // a rota normal não foi aceita
    doReturn(new ResultadoRota("t", DesfechoInteracao.CITACAO_INVALIDA, List.of()))
        .when(rotaLegislacao).responder(eq("a soja tem redução?"), any(),
            any(FontePergunta.class));
    reconferencia.reconferir(parDeLei(fatia), contexto);
    assertThat(motivo()).isEqualTo(MotivoReconferenciaLei.CORRIJO_CONCORDANCIA_NAO_ACEITA);
    assertThat(contexto.reconferenciaLei().desfechoConcordancia())
        .isEqualTo(DesfechoInteracao.CITACAO_INVALIDA);

    // aceita, mas sem frase com o número novo e sem o antigo
    contexto = new ContextoAssistente(UUID.randomUUID(), HOJE);
    concordanciaCom("A soja tem redução de 40% da alíquota [T1].", 0);
    reconferencia.reconferir(parDeLei(fatia), contexto);
    assertThat(motivo()).isEqualTo(MotivoReconferenciaLei.CORRIJO_CONCORDANCIA_SEM_FRASE);

    // o trecho da correção não está entre os da rota normal
    contexto = new ContextoAssistente(UUID.randomUUID(), HOJE);
    TrechoRecuperado outroTrecho = new TrechoRecuperado("T1", "t999", "LC 214/2025", "art. 999",
        "u", TEXTO_TRECHO, 0.9);
    doReturn(new ResultadoRota("t", DesfechoInteracao.RESPONDIDA, List.of(), List.of(),
        new DetalheLei("t", "A soja tem redução de 60% [T1].", List.of(outroTrecho))))
        .when(rotaLegislacao).responder(eq("a soja tem redução?"), any(),
            any(FontePergunta.class));
    reconferencia.reconferir(parDeLei(fatia), contexto);
    assertThat(motivo()).isEqualTo(MotivoReconferenciaLei.CORRIJO_CONCORDANCIA_SEM_TRECHO);

    // o modelo falhou na rota normal
    contexto = new ContextoAssistente(UUID.randomUUID(), HOJE);
    doThrow(new AssistenteIndisponivelException("fora", new RuntimeException("x"))).when(rotaLegislacao)
        .responder(eq("a soja tem redução?"), any(), any(FontePergunta.class));
    ReconferenciaLei.Resultado r = reconferencia.reconferir(parDeLei(fatia), contexto);
    assertThat(motivo()).isEqualTo(MotivoReconferenciaLei.CORRIJO_CONCORDANCIA_INDISPONIVEL);
    assertThat(r.desfecho()).isEqualTo(DesfechoContinuidade.RECONFERENCIA_DESCARTADA_CONCORDANCIA);
  }

  @Test
  @DisplayName("Saída do modelo descartada: formato, corte, veredito e NAO_SUSTENTA, um código cada")
  void saidaDoModelo() {
    ChatResponse cortada = new ChatResponse(List.of(new Generation(
        new AssistantMessage("{\"veredito\": \"NAO_SUSTENTA\"}"),
        ChatGenerationMetadata.builder().finishReason("length").build())));
    when(modelo.chamar(any())).thenReturn(cortada,
        resposta("texto solto"),
        resposta("{\"veredito\": \"OUTRO\"}"),
        resposta("{\"veredito\": \"NAO_SUSTENTA\"}"),
        resposta("{\"veredito\": \"CONFIRMO\", \"fundamentos\": []}"),
        resposta("{\"veredito\": \"CORRIJO\", \"correcoes\": []}"));

    List<MotivoReconferenciaLei> motivos = new java.util.ArrayList<>();
    for (int i = 0; i < 6; i++) {
      contexto = new ContextoAssistente(UUID.randomUUID(), HOJE);
      reconferencia.reconferir(parDeLei("Sim, a soja tem redução de 60% da alíquota"), contexto);
      motivos.add(motivo());
    }

    assertThat(motivos).containsExactly(MotivoReconferenciaLei.CORTADA_PELO_TETO,
        MotivoReconferenciaLei.SAIDA_NAO_E_JSON, MotivoReconferenciaLei.VEREDITO_DESCONHECIDO,
        MotivoReconferenciaLei.NAO_SUSTENTA, MotivoReconferenciaLei.CONFIRMO_FORMATO,
        MotivoReconferenciaLei.CORRIJO_FORMATO_LISTA);
  }

  @Test
  @DisplayName("Guardas do CONFIRMO e do CORRIJO: cada uma grava o seu código")
  void guardas() {
    when(modelo.chamar(any())).thenReturn(
        // CONFIRMO com a marca de um trecho que a resposta anterior não citou
        resposta("{\"veredito\": \"CONFIRMO\", \"fundamentos\": [{\"marca\": \"T2\", "
            + "\"citacao\": \"Outro trecho de lei sobre insumos e prazos\"}]}"),
        // CONFIRMO com citação que não é literal
        resposta("{\"veredito\": \"CONFIRMO\", \"fundamentos\": [{\"marca\": \"T1\", "
            + "\"citacao\": \"Redação dada pela Lei Complementar nº 227\"}]}"),
        // CONFIRMO sobre uma frase com número que o trecho não tem
        resposta("{\"veredito\": \"CONFIRMO\", \"fundamentos\": [{\"marca\": \"T1\", "
            + "\"citacao\": \"" + CITACAO + "\"}]}"),
        // CORRIJO com item incompleto
        resposta("{\"veredito\": \"CORRIJO\", \"correcoes\": [{\"fraseAnterior\": \"x\"}]}"),
        // CORRIJO com frase anterior que não está na resposta
        resposta("{\"veredito\": \"CORRIJO\", \"correcoes\": [{\"fraseAnterior\": \"isso nao "
            + "esta na resposta anterior de jeito nenhum\", \"fraseCorrigida\": \"A soja tem "
            + "redução de 60% [T1]\", \"fundamento\": {\"marca\": \"T1\", \"citacao\": \""
            + CITACAO + "\"}}]}"),
        // CORRIJO com marca que não existe entre os trechos
        resposta(correcao().replace("\"marca\": \"T1\"", "\"marca\": \"T9\"")));

    List<MotivoReconferenciaLei> motivos = new java.util.ArrayList<>();
    String[] frases = {"Sim, a soja tem redução de 60% da alíquota",
        "Sim, a soja tem redução de 60% da alíquota",
        "Sim, a soja tem redução de 40% da alíquota",
        "A soja tem redução de 40% da alíquota",
        "A soja tem redução de 40% da alíquota",
        "A soja tem redução de 40% da alíquota"};
    for (String frase : frases) {
      contexto = new ContextoAssistente(UUID.randomUUID(), HOJE);
      reconferencia.reconferir(parDeLei(frase), contexto);
      motivos.add(motivo());
    }

    assertThat(motivos).containsExactly(MotivoReconferenciaLei.CONFIRMO_MARCA_NAO_CITADA,
        MotivoReconferenciaLei.CONFIRMO_CITACAO_NAO_LITERAL,
        MotivoReconferenciaLei.CONFIRMO_NUMERO_FATIA, MotivoReconferenciaLei.CORRIJO_FORMATO_ITEM,
        MotivoReconferenciaLei.CORRIJO_SEM_ANCORA, MotivoReconferenciaLei.CORRIJO_MARCA_INEXISTENTE);
  }

  @Test
  @DisplayName("Sem modelo: disjuntor, sem dispositivo, busca que falha, sem trecho e chamada fora")
  void semModelo() {
    when(disponibilidade.disponivel()).thenReturn(false);
    reconferencia.reconferir(parDeLei("Sim, a soja tem redução de 60% da alíquota"), contexto);
    assertThat(motivo()).isEqualTo(MotivoReconferenciaLei.SEM_MODELO_DISJUNTOR);
    assertThat(contexto.reconferenciaLei().busca()).isNull();
    assertThat(contexto.reconferenciaLei().trechosPrompt()).isNull();

    when(disponibilidade.disponivel()).thenReturn(true);
    contexto = new ContextoAssistente(UUID.randomUUID(), HOJE);
    reconferencia.reconferir(parSemDispositivo(), contexto);
    assertThat(motivo()).isEqualTo(MotivoReconferenciaLei.SEM_MODELO_SEM_DISPOSITIVO);

    // a busca vetorial falha: os citados já foram contados, a busca fica FALHOU
    contexto = new ContextoAssistente(UUID.randomUUID(), HOJE);
    doThrow(new AssistenteIndisponivelException("fora", new RuntimeException("x"))).when(busca).buscar(any(), eq(HOJE));
    ReconferenciaLei.Resultado r = reconferencia.reconferir(
        parDeLei("Sim, a soja tem redução de 60% da alíquota"), contexto);
    assertThat(motivo()).isEqualTo(MotivoReconferenciaLei.SEM_MODELO_BUSCA_FALHOU);
    assertThat(contexto.reconferenciaLei().busca()).isEqualTo(BuscaReconferenciaLei.FALHOU);
    assertThat(contexto.reconferenciaLei().trechosCitadosAchados()).isEqualTo(1);
    assertThat(contexto.reconferenciaLei().trechosBusca()).isNull();
    assertThat(r.desfecho()).isEqualTo(DesfechoContinuidade.RECONFERENCIA_SEM_MODELO);
    assertThat(r.modeloRespondeu()).isFalse();

    // a busca dos citados falha: a busca vetorial nem começou
    contexto = new ContextoAssistente(UUID.randomUUID(), HOJE);
    doThrow(new AssistenteIndisponivelException("fora", new RuntimeException("x"))).when(buscaTextual)
        .buscarDispositivos(anyList(), eq(HOJE));
    reconferencia.reconferir(parDeLei("Sim, a soja tem redução de 60% da alíquota"), contexto);
    assertThat(motivo()).isEqualTo(MotivoReconferenciaLei.SEM_MODELO_BUSCA_FALHOU);
    assertThat(contexto.reconferenciaLei().busca()).isNull();

    // nenhum trecho citado na base
    contexto = new ContextoAssistente(UUID.randomUUID(), HOJE);
    doReturn(List.of()).when(buscaTextual).buscarDispositivos(anyList(), eq(HOJE));
    doReturn(List.of(OUTRO)).when(busca).buscar(any(), eq(HOJE));
    reconferencia.reconferir(parDeLei("Sim, a soja tem redução de 60% da alíquota"), contexto);
    assertThat(motivo()).isEqualTo(MotivoReconferenciaLei.SEM_MODELO_SEM_TRECHO_CITADO);
    assertThat(contexto.reconferenciaLei().trechosCitadosAchados()).isZero();

    // a chamada ao modelo falha
    contexto = new ContextoAssistente(UUID.randomUUID(), HOJE);
    doReturn(List.of(TRECHO)).when(buscaTextual).buscarDispositivos(anyList(), eq(HOJE));
    when(modelo.chamar(any())).thenThrow(new AssistenteIndisponivelException("fora", new RuntimeException("x")));
    reconferencia.reconferir(parDeLei("Sim, a soja tem redução de 60% da alíquota"), contexto);
    assertThat(motivo()).isEqualTo(MotivoReconferenciaLei.SEM_MODELO_CHAMADA_FALHOU);
    assertThat(contexto.reconferenciaLei().trechosPrompt()).isEqualTo(2);
  }

  @Test
  @DisplayName("Texto fixo: o motivo é TEXTO_FIXO e nada do prompt é registrado")
  void textoFixo() {
    ParConferido par = new ParConferido("p", "resposta anterior", "v1.a.b",
        new CargaTurno(1L, Intencao.LEGISLACAO, false, null, null, null, false,
            new LeiAssinada(LeiAssinada.Classe.SEM_FUNDAMENTO, null, List.of(), null, false, false,
                null)), 0, false);

    ReconferenciaLei.Resultado r = reconferencia.reconferir(par, contexto);

    assertThat(r.desfecho()).isEqualTo(DesfechoContinuidade.RECONFERENCIA_TEXTO_FIXO);
    assertThat(motivo()).isEqualTo(MotivoReconferenciaLei.TEXTO_FIXO);
    assertThat(contexto.reconferenciaLei().trechosPrompt()).isNull();
    assertThat(contexto.reconferenciaLei().busca()).isNull();
  }

  @Test
  @DisplayName("A instrumentação não muda a resposta nem o desfecho: texto e desfechos de sempre")
  void naoMudaARespostaNemODesfecho() {
    when(modelo.chamar(any())).thenReturn(resposta("{\"veredito\": \"CONFIRMO\", "
        + "\"fundamentos\": [{\"marca\": \"T1\", \"citacao\": \"" + CITACAO + "\"}]}"));

    ReconferenciaLei.Resultado r = reconferencia.reconferir(
        parDeLei("Sim, a soja tem redução de 40% da alíquota"), contexto);

    assertThat(motivo()).isEqualTo(MotivoReconferenciaLei.CONFIRMO_NUMERO_FATIA);
    assertThat(r.desfecho()).isEqualTo(DesfechoContinuidade.RECONFERENCIA_DESCARTADA_NUMERO);
    assertThat(r.modeloRespondeu()).isTrue();
    assertThat(r.rota().desfecho()).isEqualTo(DesfechoInteracao.RECONFERENCIA_DESCARTADA);
    assertThat(r.rota().texto()).isEqualTo("A resposta anterior se baseia em " + REFERENCIA
        + ". O texto de cada dispositivo está abaixo, com o link. "
        + ReconferenciaLei.ENQUADRAMENTO);
    assertThat(r.rota().citacoes()).hasSize(1);
    assertThat(contexto.registroTurno().reconferida()).isTrue();
  }

  @Test
  @DisplayName("Guardas restantes do CONFIRMO e do CORRIJO: cada uma grava o seu código")
  void guardasRestantes() {
    String semNumero = "alíquotas do IBS e da CBS incidentes sobre o fornecimento dos produtos "
        + "agropecuários";
    String de40 = "A soja tem redução de 40% da alíquota [T1]";
    String de60 = "A soja tem redução de 60% da alíquota [T1]";
    when(modelo.chamar(any())).thenReturn(
        // CONFIRMO cuja citação literal não traz o número da fatia
        resposta("{\"veredito\": \"CONFIRMO\", \"fundamentos\": [{\"marca\": \"T1\", "
            + "\"citacao\": \"" + semNumero + "\"}]}"),
        // CORRIJO com citação do fundamento tirada da anotação
        resposta(correcao(de40, de60, "Redação dada pela Lei Complementar nº 227")),
        // CORRIJO cuja frase corrigida não leva a marca
        resposta(correcao(de40, "A soja tem redução de 60% da alíquota", CITACAO)),
        // CORRIJO com número que o trecho não tem
        resposta(correcao(de40, "A soja tem redução de 70% da alíquota [T1]", CITACAO)),
        // CORRIJO que fala dos dados do produtor
        resposta(correcao(de40, "Você paga e a soja tem redução de 60% da alíquota [T1]",
            CITACAO)),
        // CORRIJO sem diferença: o mesmo número de volta
        resposta(correcao(de60, de60, CITACAO)),
        // CORRIJO que inverte sim e não, com a rota normal dizendo sim
        resposta(correcao("Sim, a soja tem redução de 60% da alíquota [T1]",
            "Não, a soja tem redução de 60% da alíquota [T1]", CITACAO)));
    concordanciaCom("Sim, a soja tem redução de 60% da alíquota [T1].", 0);

    String[] frases = {"Sim, a soja tem redução de 60% da alíquota",
        "A soja tem redução de 40% da alíquota", "A soja tem redução de 40% da alíquota",
        "A soja tem redução de 40% da alíquota", "A soja tem redução de 40% da alíquota",
        "A soja tem redução de 60% da alíquota", "Sim, a soja tem redução de 60% da alíquota"};
    List<MotivoReconferenciaLei> motivos = new java.util.ArrayList<>();
    for (String frase : frases) {
      contexto = new ContextoAssistente(UUID.randomUUID(), HOJE);
      reconferencia.reconferir(parDeLei(frase), contexto);
      motivos.add(motivo());
    }

    assertThat(motivos).containsExactly(MotivoReconferenciaLei.CONFIRMO_NUMERO_CITACAO,
        MotivoReconferenciaLei.CORRIJO_CITACAO_NAO_LITERAL,
        MotivoReconferenciaLei.CORRIJO_CITACAO_INVALIDA, MotivoReconferenciaLei.CORRIJO_NUMERO,
        MotivoReconferenciaLei.CORRIJO_DADOS, MotivoReconferenciaLei.CORRIJO_SEM_DIFERENCA,
        MotivoReconferenciaLei.CORRIJO_CONCORDANCIA_POLARIDADE);
  }

  @Test
  @DisplayName("MISTO: os trechos das compras entram na contagem, à parte dos da busca vetorial")
  void mistoContaOsTrechosDasCompras() {
    String fatia = "A soja tem redução (" + REFERENCIA + ").";
    ParConferido misto = new ParConferido("p", "Dados.\n\n" + fatia, "v1.a.b",
        new CargaTurno(1L, Intencao.MISTO, false, null, null, null, false,
            new LeiAssinada(LeiAssinada.Classe.CONFIRMADA, new Faixa(8, 8 + fatia.length()), null,
                "a soja tem redução?", false, false, null)), 0, false);
    when(trechosDasCompras.buscar(any())).thenReturn(List.of(new TrechoRecuperado(null, "t9",
        "LC 214/2025", "Anexo IX, item 4", "u", "Item de anexo das compras do produtor.", 0.4)));
    when(modelo.chamar(any())).thenReturn(resposta("{\"veredito\": \"NAO_SUSTENTA\"}"));

    reconferencia.reconferir(misto, contexto);

    RegistroReconferenciaLei registro = contexto.reconferenciaLei();
    assertThat(registro.motivo()).isEqualTo(MotivoReconferenciaLei.NAO_SUSTENTA);
    assertThat(registro.trechosBusca()).isEqualTo(2);
    assertThat(registro.trechosCompras()).isEqualTo(1);
    assertThat(registro.trechosPrompt()).isEqualTo(3);
  }

  @Test
  @DisplayName("Concordância que falha: nenhum token vai à auditoria (a estimativa não é gasto)")
  void concordanciaQueFalhaNaoGravaTokens() {
    when(modelo.chamar(any())).thenReturn(resposta(correcao()));
    doThrow(new AssistenteIndisponivelException("fora", new RuntimeException("x")))
        .when(rotaLegislacao).responder(eq("a soja tem redução?"), any(),
            any(FontePergunta.class));

    reconferencia.reconferir(parDeLei("A soja tem redução de 40% da alíquota"), contexto);

    assertThat(motivo()).isEqualTo(MotivoReconferenciaLei.CORRIJO_CONCORDANCIA_INDISPONIVEL);
    assertThat(contexto.reconferenciaLei().tokensEntradaConcordancia()).isNull();
    assertThat(contexto.reconferenciaLei().desfechoConcordancia()).isNull();
  }

  @Test
  @DisplayName("Nenhum código do motivo cabe mal na coluna de 40 caracteres")
  void codigosCabemNaColuna() {
    assertThat(java.util.Arrays.stream(MotivoReconferenciaLei.values())
        .mapToInt(m -> m.name().length()).max().orElseThrow()).isLessThanOrEqualTo(40);
    assertThat(java.util.Arrays.stream(BuscaReconferenciaLei.values())
        .mapToInt(m -> m.name().length()).max().orElseThrow()).isLessThanOrEqualTo(10);
  }

  private ParConferido parDeLei(String frase) {
    String fatia = frase + " (" + REFERENCIA + ").";
    String resposta = fatia + "\n\nO que isso quer dizer: IBS é o imposto.";
    return new ParConferido("a soja tem redução?", resposta, "v1.a.b",
        new CargaTurno(1L, Intencao.LEGISLACAO, false, null, null, null, false,
            new LeiAssinada(LeiAssinada.Classe.CONFIRMADA, new Faixa(0, fatia.length()), null,
                null, false, false, null)), 0, false);
  }

  private ParConferido parSemDispositivo() {
    String fatia = "A soja tem redução de 60% da alíquota.";
    return new ParConferido("a soja tem redução?", fatia, "v1.a.b",
        new CargaTurno(1L, Intencao.LEGISLACAO, false, null, null, null, false,
            new LeiAssinada(LeiAssinada.Classe.CONFIRMADA, new Faixa(0, fatia.length()), null,
                null, false, false, null)), 0, false);
  }

  /** A rota de lei normal da concordância, gastando {@code tokensEntrada} na chamada dela. */
  private void concordanciaCom(String textoComMarcas, int tokensEntrada) {
    TrechoRecuperado t1 = new TrechoRecuperado("T1", "t137", "LC 214/2025", "art. 137, § 1º",
        "u", TEXTO_TRECHO, 0.9);
    ResultadoRota rota = new ResultadoRota("texto", DesfechoInteracao.RESPONDIDA, List.of(),
        List.of(), new DetalheLei("texto", textoComMarcas, List.of(t1)));
    doAnswer(invocacao -> {
      ContextoAssistente chamador = invocacao.getArgument(1);
      if (tokensEntrada > 0) {
        chamador.registrarUso(new Prompt("x"), respostaComUso(tokensEntrada, 50));
      }
      return rota;
    }).when(rotaLegislacao).responder(eq("a soja tem redução?"), any(),
        any(FontePergunta.class));
  }

  private static String correcao(String anterior, String corrigida, String citacao) {
    return "{\"veredito\": \"CORRIJO\", \"correcoes\": [{\"fraseAnterior\": \"" + anterior
        + "\", \"fraseCorrigida\": \"" + corrigida + "\", \"fundamento\": {\"marca\": \"T1\", "
        + "\"citacao\": \"" + citacao + "\"}}]}";
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

  private static ChatResponse respostaComUso(int entrada, int saida) {
    return new ChatResponse(List.of(new Generation(new AssistantMessage("x"))),
        ChatResponseMetadata.builder().usage(new DefaultUsage(entrada, saida)).build());
  }
}
