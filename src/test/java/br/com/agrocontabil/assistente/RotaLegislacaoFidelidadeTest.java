package br.com.agrocontabil.assistente;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.assistente.fidelidade.ConferenciaFidelidadeLei;
import br.com.agrocontabil.assistente.fidelidade.JuizFidelidadeLei;
import br.com.agrocontabil.assistente.llm.DisponibilidadeModelo;
import br.com.agrocontabil.assistente.llm.ModeloLinguagem;
import br.com.agrocontabil.assistente.rag.BuscaLegislacaoService;
import br.com.agrocontabil.assistente.rag.CitacaoLegal;
import br.com.agrocontabil.assistente.rag.TrechoRecuperado;
import br.com.agrocontabil.assistente.rag.ValidadorCitacao;
import br.com.agrocontabil.config.AssistenteProperties;
import br.com.agrocontabil.model.DesfechoFidelidadeLei;
import br.com.agrocontabil.model.DesfechoInteracao;
import br.com.agrocontabil.support.ChatModelSimulado;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * A conferência de fidelidade na {@link RotaLegislacao} (#269): desligada, a rota é a de antes,
 * prompt e resultado iguais; ligada, o prompt pede a evidência por frase, a resposta reprovada cai no
 * caminho da lei descartada e a aprovada segue pelos validadores de sempre, com a citação no nível do
 * dispositivo em que a evidência está.
 */
class RotaLegislacaoFidelidadeTest {

  private static final String URL = "https://www.planalto.gov.br/ccivil_03/leis/lcp/lcp214.htm";
  private static final String CAPUT = "Art. 138. Ficam reduzidas em 60% as aliquotas do IBS e da CBS"
      + " incidentes sobre os insumos agropecuarios.";
  private static final TrechoRecuperado INSUMOS = new TrechoRecuperado("T1", "id-1", "LC 214/2025",
      "art. 138", URL, CAPUT, 0.9);
  private static final TrechoRecuperado PARAGRAFO = new TrechoRecuperado("T1", "id-par",
      "LC 214/2025", "art. 138, § 1º", URL,
      CAPUT + " § 1º O disposto neste artigo aplica-se aos fertilizantes e defensivos.", 0.9);
  private static final LocalDate HOJE = LocalDate.of(2026, 11, 12);

  private BuscaLegislacaoService busca;
  private ChatModelSimulado chatModel;
  private ModeloLinguagem modelo;
  private ContextoAssistente contexto;

  @BeforeEach
  void setUp() {
    busca = mock(BuscaLegislacaoService.class);
    chatModel = new ChatModelSimulado();
    StaticListableBeanFactory beans = new StaticListableBeanFactory();
    beans.addBean("chatModel", chatModel);
    modelo = new ModeloLinguagem(beans.getBeanProvider(ChatModel.class), "ollama",
        new DisponibilidadeModelo(Duration.ofSeconds(30), System::nanoTime));
    contexto = new ContextoAssistente(UUID.randomUUID(), HOJE);
  }

  private RotaLegislacao rota(boolean ligada, double cobertura) {
    return rota(ligada, cobertura, false);
  }

  private RotaLegislacao rota(boolean ligada, double cobertura, boolean juiz) {
    return rota(ligada, cobertura, juiz, false, false);
  }

  private RotaLegislacao rota(boolean ligada, double cobertura, boolean juiz, boolean resgate,
      boolean resgateCobertura) {
    return rota(ligada, cobertura, juiz, resgate, true, resgateCobertura);
  }

  private RotaLegislacao rota(boolean ligada, double cobertura, boolean juiz, boolean resgate,
      boolean resgatePolaridade, boolean resgateCobertura) {
    return rota(ligada, cobertura, juiz, true, resgate, resgatePolaridade, resgateCobertura);
  }

  private RotaLegislacao rota(boolean ligada, double cobertura, boolean juiz, boolean veto,
      boolean resgate, boolean resgatePolaridade, boolean resgateCobertura) {
    AssistenteProperties propriedades = new AssistenteProperties();
    propriedades.getFidelidadeLei().setEnabled(ligada);
    propriedades.getFidelidadeLei().setCoberturaMinima(cobertura);
    propriedades.getFidelidadeLei().getJuiz().setEnabled(juiz);
    propriedades.getFidelidadeLei().getJuiz().getResgate().setEnabled(resgate);
    propriedades.getFidelidadeLei().getJuiz().setVeto(veto);
    propriedades.getFidelidadeLei().getJuiz().getResgate().setPolaridade(resgatePolaridade);
    propriedades.getFidelidadeLei().getJuiz().getResgate().setCobertura(resgateCobertura);
    return new RotaLegislacao(modelo, busca, new ValidadorCitacao(), new ValidadorNumeros(),
        new VerificadorAfirmacaoDados(), propriedades,
        new ConferenciaFidelidadeLei(GlossarioProdutor.padrao()),
        new JuizFidelidadeLei(modelo, propriedades));
  }

  private RotaLegislacao comJuiz() {
    return rota(true, 0.7, true);
  }

  private RotaLegislacao ligada() {
    return rota(true, 0.7);
  }

  private RotaLegislacao antiga() {
    return new RotaLegislacao(modelo, busca, new ValidadorCitacao(), new ValidadorNumeros(),
        new VerificadorAfirmacaoDados(), new AssistenteProperties());
  }

  private static String json(String frase, String trecho, String copia) {
    return "{\"frases\": [{\"frase\": \"" + frase + "\", \"evidencias\": [{\"trecho\": \""
        + trecho + "\", \"copia\": \"" + copia + "\"}]}]}";
  }

  private String sistemaRecebido() {
    return chatModel.promptsRecebidos().get(chatModel.promptsRecebidos().size() - 1)
        .getSystemMessage().getText();
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "A reducao para insumos agropecuarios e de 60% [T1].",
      "O IBS dos insumos tem reducao de 70% [T1].",
      "A reducao e de 60% [T1], e ha isencao total [T4].",
      "Ha isencao total [T4].",
      "NAO_ENCONTREI",
      "Resposta sem marca.",
      "{\"frases\": [{\"frase\": \"Reducao de 60%\", \"evidencias\": []}]}"})
  @DisplayName("flag desligada: o mesmo prompt e o mesmo resultado da rota sem a conferência")
  void desligadaEhARotaDeAntes(String respostaDoModelo) {
    when(busca.buscar(anyString(), any())).thenReturn(List.of(INSUMOS));
    chatModel.responderTexto(respostaDoModelo);
    ResultadoRota antes = antiga().responder("Qual a reducao dos insumos?", contexto);
    String promptAntes = sistemaRecebido();

    ContextoAssistente outro = new ContextoAssistente(UUID.randomUUID(), HOJE);
    // O juiz ligado sem a camada 1 nao tem efeito (#269).
    ResultadoRota depois = rota(false, 0.7, true).responder("Qual a reducao dos insumos?", outro);

    assertThat(chatModel.promptsRecebidos()).hasSize(2);
    assertThat(outro.juizLei()).isNull();
    assertThat(sistemaRecebido()).isEqualTo(promptAntes)
        .isEqualTo(PromptsCeres.sistemaLegislacao(HOJE, RotaLegislacao.formatar(List.of(INSUMOS))));
    assertThat(depois).isEqualTo(antes);
    assertThat(outro.fidelidadeLei()).isNull();
    assertThat(outro.trechosCitados()).isEqualTo(contexto.trechosCitados());
  }

  @Test
  @DisplayName("flag desligada no MISTO: o prompt da regra geral de sempre")
  void desligadaNoMistoUsaOPromptDeSempre() {
    when(busca.buscar(anyString(), any())).thenReturn(List.of(INSUMOS));
    chatModel.responderTexto("Os insumos agropecuarios tem reducao de 60% [T1].");

    rota(false, 0.7).responderRegraGeral("Insumo tem reducao?", contexto, List.of(), true);

    assertThat(sistemaRecebido()).isEqualTo(
        PromptsCeres.sistemaLegislacaoRegraGeral(HOJE, RotaLegislacao.formatar(List.of(INSUMOS))));
  }

  @Test
  @DisplayName("flag ligada: prompt com evidência, parte fixa antes da data e dos trechos")
  void ligadaPedeEvidencia() {
    when(busca.buscar(anyString(), any())).thenReturn(List.of(INSUMOS));
    chatModel.responderTexto("NAO_ENCONTREI");

    ligada().responder("Qual a reducao dos insumos?", contexto);

    String sistema = sistemaRecebido();
    assertThat(sistema).isEqualTo(PromptsCeres.sistemaLegislacaoComEvidencia(HOJE,
        RotaLegislacao.formatar(List.of(INSUMOS))));
    assertThat(sistema).contains("\"copia\"", "palavra por palavra", "NAO_ENCONTREI");
    assertThat(sistema.indexOf("\"frases\"")).isLessThan(sistema.indexOf("Hoje e"));
    // NAO_ENCONTREI: a conferencia nao roda.
    assertThat(contexto.fidelidadeLei()).isNull();
  }

  @Test
  @DisplayName("ligada: NAO_ENCONTREI ao lado de frases não derruba a resposta; sozinho, sim")
  void naoEncontreiParcialNaoDerruba() {
    when(busca.buscar(anyString(), any())).thenReturn(List.of(INSUMOS));
    chatModel.responderTexto("{\"frases\": [{\"frase\": \"Os insumos agropecuarios tem as "
        + "aliquotas reduzidas em 60%.\", \"evidencias\": [{\"trecho\": \"T1\", \"copia\": "
        + "\"Ficam reduzidas em 60% as aliquotas do IBS e da CBS incidentes sobre os insumos "
        + "agropecuarios\"}]}, {\"frase\": \"NAO_ENCONTREI\", \"evidencias\": []}]}");

    ResultadoRota r = ligada().responder("Insumos tem reducao? E maquinas?", contexto);

    assertThat(r.desfecho()).isEqualTo(DesfechoInteracao.RESPONDIDA);
    assertThat(r.texto()).startsWith("Os insumos agropecuarios tem as aliquotas reduzidas em 60% "
        + "(LC 214/2025, art. 138).").doesNotContain("NAO_ENCONTREI");

    for (String so : List.of("NAO_ENCONTREI",
        "{\"frases\": [{\"frase\": \"NAO_ENCONTREI\", \"evidencias\": []}]}")) {
      chatModel.responderTexto(so);
      ContextoAssistente outro = new ContextoAssistente(UUID.randomUUID(), HOJE);
      ResultadoRota sozinho = ligada().responder("Maquinas tem reducao?", outro);
      assertThat(sozinho.desfecho()).as(so).isEqualTo(DesfechoInteracao.CITACAO_INVALIDA);
      assertThat(sozinho.texto()).isEqualTo(RotaLegislacao.TRECHOS_SEM_CONFIRMACAO);
      assertThat(outro.fidelidadeLei()).isNull();
    }
  }

  @Test
  @DisplayName("ligada: o prompt deixa responder a parte que os trechos sustentam")
  void promptAceitaRespostaParcial() {
    assertThat(PromptsCeres.sistemaLegislacaoComEvidencia(HOJE, "[T1] x"))
        .contains("responda essa parte", "So se nenhum trecho tratar do assunto");
    // O prompt de sempre nao muda.
    assertThat(PromptsCeres.sistemaLegislacao(HOJE, "[T1] x"))
        .contains("Se os trechos nao responderem a pergunta, responda apenas: NAO_ENCONTREI")
        .doesNotContain("responda essa parte");
  }

  @Test
  @DisplayName("ligada e aprovada: as frases com as referências, a linha do glossário e as citações")
  void ligadaAprovada() {
    when(busca.buscar(anyString(), any())).thenReturn(List.of(INSUMOS));
    chatModel.responderTexto(json("O IBS e a CBS dos insumos agropecuarios tem reducao de 60%.",
        "T1", "Ficam reduzidas em 60% as aliquotas do IBS e da CBS incidentes sobre os insumos "
            + "agropecuarios"));

    ResultadoRota r = ligada().responder("Qual a reducao dos insumos?", contexto);

    assertThat(r.desfecho()).isEqualTo(DesfechoInteracao.RESPONDIDA);
    assertThat(r.texto()).startsWith("O IBS e a CBS dos insumos agropecuarios tem reducao de 60% "
        + "(LC 214/2025, art. 138).")
        .contains(LinhaGlossario.TITULO)
        .doesNotContain("\"copia\"", "Ficam reduzidas");
    assertThat(r.citacoes()).extracting(CitacaoLegal::dispositivo).containsExactly("art. 138");
    assertThat(contexto.fidelidadeLei()).isEqualTo(DesfechoFidelidadeLei.APROVADA);
    assertThat(contexto.trechosCitados()).containsExactly("id-1");
  }

  @Test
  @DisplayName("ligada, evidência no caput e marca no parágrafo: a citação vai para o artigo")
  void ligadaCorrigeParaOCaput() {
    when(busca.buscar(anyString(), any())).thenReturn(List.of(PARAGRAFO));
    chatModel.responderTexto(json("Os insumos agropecuarios tem as aliquotas reduzidas em 60%.",
        "T1", "Ficam reduzidas em 60% as aliquotas do IBS e da CBS incidentes sobre os insumos "
            + "agropecuarios"));

    ResultadoRota r = ligada().responder("Qual a reducao dos insumos?", contexto);

    assertThat(r.desfecho()).isEqualTo(DesfechoInteracao.RESPONDIDA);
    assertThat(r.texto()).startsWith("Os insumos agropecuarios tem as aliquotas reduzidas em 60% "
        + "(LC 214/2025, art. 138).");
    assertThat(r.citacoes()).singleElement().satisfies(c -> {
      assertThat(c.dispositivo()).isEqualTo("art. 138");
      assertThat(c.trecho()).isEqualTo(CAPUT);
    });
    assertThat(contexto.fidelidadeLei())
        .isEqualTo(DesfechoFidelidadeLei.APROVADA_CITACAO_CORRIGIDA);
    assertThat(contexto.trechosCitados()).containsExactly("id-par");
  }

  @Test
  @DisplayName("ligada, caput e parágrafo do mesmo trecho citados: o id vai uma vez à auditoria")
  void ligadaNaoRepeteIdNaAuditoria() {
    when(busca.buscar(anyString(), any())).thenReturn(List.of(PARAGRAFO));
    chatModel.responderTexto("{\"frases\": ["
        + "{\"frase\": \"Os insumos agropecuarios tem as aliquotas reduzidas em 60%.\", "
        + "\"evidencias\": [{\"trecho\": \"T1\", \"copia\": \"Ficam reduzidas em 60% as aliquotas"
        + " do IBS e da CBS incidentes sobre os insumos agropecuarios\"}]},"
        + "{\"frase\": \"Isso se aplica aos fertilizantes e defensivos.\", \"evidencias\": ["
        + "{\"trecho\": \"T1\", \"copia\": \"aplica-se aos fertilizantes e defensivos\"}]}]}");

    ResultadoRota r = ligada().responder("Qual a reducao dos insumos?", contexto);

    assertThat(r.desfecho()).isEqualTo(DesfechoInteracao.RESPONDIDA);
    assertThat(r.citacoes()).extracting(CitacaoLegal::dispositivo)
        .containsExactly("art. 138", "art. 138, § 1º");
    assertThat(contexto.trechosCitados()).containsExactly("id-par");
  }

  @Test
  @DisplayName("ligada e reprovada: sai o texto do modelo, ficam os trechos que ele marcou")
  void ligadaReprovadaFicamOsTrechos() {
    when(busca.buscar(anyString(), any())).thenReturn(List.of(INSUMOS));
    chatModel.responderTexto(json("Os insumos agropecuarios nao tem reducao.", "T1",
        "Ficam reduzidas em 60% as aliquotas do IBS e da CBS incidentes sobre os insumos "
            + "agropecuarios"));

    ResultadoRota r = ligada().responder("Qual a reducao dos insumos?", contexto);

    assertThat(r.desfecho()).isEqualTo(DesfechoInteracao.FIDELIDADE_REPROVADA);
    assertThat(r.texto()).isEqualTo(RotaLegislacao.TRECHOS_SEM_CONFIRMACAO);
    assertThat(r.soTrechos()).isTrue();
    assertThat(r.citacoes()).extracting(CitacaoLegal::dispositivo).containsExactly("art. 138");
    assertThat(contexto.fidelidadeLei()).isEqualTo(DesfechoFidelidadeLei.REPROVADA_POLARIDADE);
    assertThat(contexto.trechosCitados()).containsExactly("id-1");
    assertThat(contexto.trechosRelacionados()).isFalse();
  }

  @Test
  @DisplayName("ligada, resposta fora do formato: os trechos mais próximos, sem o texto")
  void ligadaFormatoInvalidoMostraRelacionados() {
    when(busca.buscar(anyString(), any())).thenReturn(List.of(INSUMOS));
    chatModel.responderTexto("A reducao para insumos agropecuarios e de 60% [T1].");

    ResultadoRota r = ligada().responder("Qual a reducao dos insumos?", contexto);

    assertThat(r.desfecho()).isEqualTo(DesfechoInteracao.FIDELIDADE_REPROVADA);
    assertThat(r.texto()).isEqualTo(RotaLegislacao.TRECHOS_SEM_CONFIRMACAO);
    assertThat(r.citacoes()).extracting(CitacaoLegal::dispositivo).containsExactly("art. 138");
    assertThat(contexto.fidelidadeLei()).isEqualTo(DesfechoFidelidadeLei.REPROVADA_FORMATO);
    assertThat(contexto.trechosRelacionados()).isTrue();
  }

  @Test
  @DisplayName("ligada no MISTO com a pergunta inteira e formato inválido: nada da lei (#260)")
  void ligadaNoMistoSemParteLegal() {
    when(busca.buscar(anyString(), any())).thenReturn(List.of(INSUMOS));
    chatModel.responderTexto("texto solto");

    ResultadoRota r = ligada().responderRegraGeral("Meus insumos tem reducao?", contexto,
        List.of());

    assertThat(r.desfecho()).isEqualTo(DesfechoInteracao.FIDELIDADE_REPROVADA);
    assertThat(r.texto()).isEqualTo(ValidadorCitacao.SEM_FUNDAMENTO);
    assertThat(r.citacoes()).isEmpty();
    assertThat(sistemaRecebido()).isEqualTo(PromptsCeres.sistemaLegislacaoRegraGeralComEvidencia(
        HOJE, RotaLegislacao.formatar(List.of(INSUMOS))));
  }

  @Test
  @DisplayName("ligada no MISTO: a aprovada ainda passa pelo verificador dos dados do produtor")
  void ligadaNoMistoContinuaBarrandoDados() {
    when(busca.buscar(anyString(), any())).thenReturn(List.of(INSUMOS));
    chatModel.responderTexto(json(
        "Voce tem as aliquotas dos insumos agropecuarios reduzidas em 60%.", "T1",
        "Ficam reduzidas em 60% as aliquotas do IBS e da CBS incidentes sobre os insumos "
            + "agropecuarios"));

    ResultadoRota r = ligada().responderRegraGeral("Meus insumos tem reducao?", contexto,
        List.of(), true);

    assertThat(r.desfecho()).isEqualTo(DesfechoInteracao.AFIRMACAO_SOBRE_DADOS);
    assertThat(r.texto()).isEqualTo(RotaLegislacao.SO_TRECHOS);
    assertThat(contexto.fidelidadeLei()).isEqualTo(DesfechoFidelidadeLei.APROVADA);
  }

  @Test
  @DisplayName("a cobertura mínima vem da propriedade")
  void coberturaMinimaConfiguravel() {
    when(busca.buscar(anyString(), any())).thenReturn(List.of(INSUMOS));
    // "fertilizantes" nao esta na evidencia: 4 de 5 palavras de conteudo cobertas (0,8).
    String resposta = json("Os insumos agropecuarios e fertilizantes tem aliquotas reduzidas.",
        "T1", "Ficam reduzidas em 60% as aliquotas do IBS e da CBS incidentes sobre os insumos "
            + "agropecuarios");

    chatModel.responderTexto(resposta);
    assertThat(rota(true, 0.7).responder("Insumos?", contexto).desfecho())
        .isEqualTo(DesfechoInteracao.RESPONDIDA);

    chatModel.responderTexto(resposta);
    ContextoAssistente outro = new ContextoAssistente(UUID.randomUUID(), HOJE);
    assertThat(rota(true, 1.0).responder("Insumos?", outro).desfecho())
        .isEqualTo(DesfechoInteracao.FIDELIDADE_REPROVADA);
    assertThat(outro.fidelidadeLei()).isEqualTo(DesfechoFidelidadeLei.REPROVADA_COBERTURA);
  }

  @Test
  @DisplayName("ligada: a guarda do regime específico continua depois da conferência")
  void ligadaComGuardaDoRegimeEspecifico() {
    TrechoRecuperado combustivel = new TrechoRecuperado("T1", "id-c", "LC 214/2025", "art. 172",
        URL, "Art. 172. A base de calculo do IBS e da CBS sobre combustiveis e a quantidade.", 0.9);
    when(busca.buscar(anyString(), any())).thenReturn(List.of(combustivel));
    chatModel.responderTexto(json("A base de calculo do IBS e da CBS e a quantidade.", "T1",
        "A base de calculo do IBS e da CBS sobre combustiveis e a quantidade"));

    ResultadoRota r = ligada().responder("Qual a base de calculo do IBS?", contexto);

    assertThat(r.desfecho()).isEqualTo(DesfechoInteracao.REGIME_ESPECIFICO_SEM_SETOR);
    assertThat(contexto.fidelidadeLei()).isEqualTo(DesfechoFidelidadeLei.APROVADA);
  }
  private static final String SUSTENTADA = "{\"frases\": [{\"frase\": \"Os insumos "
      + "agropecuarios tem as aliquotas reduzidas em 60%.\", \"evidencias\": [{\"trecho\": "
      + "\"T1\", \"copia\": \"Ficam reduzidas em 60% as aliquotas do IBS e da CBS incidentes "
      + "sobre os insumos agropecuarios\"}]}]}";

  @Test
  @DisplayName("juiz desligado com a camada 1 ligada: uma chamada só, sem métricas do juiz")
  void juizDesligadoNaoChama() {
    when(busca.buscar(anyString(), any())).thenReturn(List.of(INSUMOS));
    chatModel.responderTexto(SUSTENTADA);

    ResultadoRota r = ligada().responder("Qual a reducao dos insumos?", contexto);

    assertThat(r.desfecho()).isEqualTo(DesfechoInteracao.RESPONDIDA);
    assertThat(chatModel.promptsRecebidos()).hasSize(1);
    assertThat(contexto.juizLei()).isNull();
  }

  @Test
  @DisplayName("juiz ligado e SUSTENTA: a resposta sai, depois de uma segunda chamada com o juiz")
  void juizAprova() {
    when(busca.buscar(anyString(), any())).thenReturn(List.of(INSUMOS));
    chatModel.responderEmSequencia(ChatModelSimulado.texto(SUSTENTADA),
        ChatModelSimulado.texto("{\"vereditos\": [\"SUSTENTA\"]}"));

    ResultadoRota r = comJuiz().responder("Qual a reducao dos insumos?", contexto);

    assertThat(r.desfecho()).isEqualTo(DesfechoInteracao.RESPONDIDA);
    assertThat(r.texto()).startsWith("Os insumos agropecuarios tem as aliquotas reduzidas em 60% "
        + "(LC 214/2025, art. 138).");
    assertThat(chatModel.promptsRecebidos()).hasSize(2);
    assertThat(sistemaRecebido()).isEqualTo(PromptsCeres.JUIZ_FIDELIDADE);
    assertThat(chatModel.promptsRecebidos().get(1).getUserMessage().getText())
        .startsWith("Frase 1: Os insumos agropecuarios tem as aliquotas reduzidas em 60%.")
        // A passagem e o dispositivo citado inteiro (o caput, aqui), nao so a copia.
        .contains("Passagem: " + CAPUT)
        .doesNotContain("Qual a reducao");
    assertThat(contexto.fidelidadeLei()).isEqualTo(DesfechoFidelidadeLei.APROVADA);
    assertThat(contexto.juizLei()).isNotNull();
  }

  @Test
  @DisplayName("juiz ligado e NAO_SUSTENTA, CONTRADIZ ou falha: o caminho da camada 1")
  void juizReprova() {
    when(busca.buscar(anyString(), any())).thenReturn(List.of(INSUMOS));
    for (var caso : java.util.Map.of(
        "{\"vereditos\": [\"NAO_SUSTENTA\"]}", DesfechoFidelidadeLei.REPROVADA_JUIZ_NAO_SUSTENTA,
        "{\"vereditos\": [\"CONTRADIZ\"]}", DesfechoFidelidadeLei.REPROVADA_JUIZ_CONTRADIZ,
        "{\"vereditos\": []}", DesfechoFidelidadeLei.REPROVADA_JUIZ_FALHA).entrySet()) {
      chatModel.responderEmSequencia(ChatModelSimulado.texto(SUSTENTADA),
          ChatModelSimulado.texto(caso.getKey()));
      ContextoAssistente c = new ContextoAssistente(UUID.randomUUID(), HOJE);

      ResultadoRota r = comJuiz().responder("Qual a reducao dos insumos?", c);

      assertThat(r.desfecho()).as(caso.getKey()).isEqualTo(DesfechoInteracao.FIDELIDADE_REPROVADA);
      assertThat(r.texto()).isEqualTo(RotaLegislacao.TRECHOS_SEM_CONFIRMACAO);
      assertThat(r.citacoes()).extracting(CitacaoLegal::dispositivo).containsExactly("art. 138");
      assertThat(c.fidelidadeLei()).isEqualTo(caso.getValue());
      assertThat(c.trechosCitados()).containsExactly("id-1");
    }
  }

  @Test
  @DisplayName("juiz ligado: a cobertura exigida em código cai para a com juiz (0,3)")
  void juizBaixaACobertura() {
    when(busca.buscar(anyString(), any())).thenReturn(List.of(INSUMOS));
    // "fertilizantes" e "defensivos" fora da copia: 4 de 6 palavras de conteudo (0,67).
    String parafrase = "{\"frases\": [{\"frase\": \"Fertilizantes e defensivos sao insumos "
        + "agropecuarios com aliquotas reduzidas.\", \"evidencias\": [{\"trecho\": \"T1\", "
        + "\"copia\": \"Ficam reduzidas em 60% as aliquotas do IBS e da CBS incidentes sobre os "
        + "insumos agropecuarios\"}]}]}";
    chatModel.responderTexto(parafrase);
    assertThat(ligada().responder("Insumos?", contexto).desfecho())
        .isEqualTo(DesfechoInteracao.FIDELIDADE_REPROVADA);

    chatModel.responderEmSequencia(ChatModelSimulado.texto(parafrase),
        ChatModelSimulado.texto("{\"vereditos\": [\"NAO_SUSTENTA\"]}"));
    ContextoAssistente outro = new ContextoAssistente(UUID.randomUUID(), HOJE);
    assertThat(comJuiz().responder("Insumos?", outro).desfecho())
        .isEqualTo(DesfechoInteracao.FIDELIDADE_REPROVADA);
    // Passou da camada 1 (com o piso de 0,3) e foi o juiz quem reprovou.
    assertThat(outro.fidelidadeLei()).isEqualTo(DesfechoFidelidadeLei.REPROVADA_JUIZ_NAO_SUSTENTA);
  }

  @Test
  @DisplayName("juiz ligado não é chamado quando a camada 1 ou os validadores já descartam")
  void juizNaoGastaChamadaEmDescarte() {
    when(busca.buscar(anyString(), any())).thenReturn(List.of(INSUMOS));
    chatModel.responderTexto("texto fora do formato");
    assertThat(comJuiz().responder("Insumos?", contexto).desfecho())
        .isEqualTo(DesfechoInteracao.FIDELIDADE_REPROVADA);
    assertThat(chatModel.promptsRecebidos()).hasSize(1);
    assertThat(contexto.juizLei()).isNull();

    TrechoRecuperado combustivel = new TrechoRecuperado("T1", "id-c", "LC 214/2025", "art. 172",
        URL, "Art. 172. A base de calculo do IBS e da CBS sobre combustiveis e a quantidade.", 0.9);
    when(busca.buscar(anyString(), any())).thenReturn(List.of(combustivel));
    chatModel.responderTexto(json("A base de calculo do IBS e da CBS e a quantidade.", "T1",
        "A base de calculo do IBS e da CBS sobre combustiveis e a quantidade"));
    ContextoAssistente outro = new ContextoAssistente(UUID.randomUUID(), HOJE);
    assertThat(comJuiz().responder("Qual a base de calculo do IBS?", outro).desfecho())
        .isEqualTo(DesfechoInteracao.REGIME_ESPECIFICO_SEM_SETOR);
    assertThat(outro.juizLei()).isNull();
  }
  /** O número 2032 está na pergunta (o ValidadorNumeros aceita), mas fora do dispositivo. */
  private static final String NUMERO_FORA_DA_COPIA = json(
      "A reducao de 60% das aliquotas dos insumos agropecuarios vale ate 2032.", "T1",
      "Ficam reduzidas em 60% as aliquotas do IBS e da CBS incidentes sobre os insumos "
          + "agropecuarios");
  private static final String NEGA_SEM_A_COPIA = json("Os insumos agropecuarios nao tem reducao.",
      "T1", "Ficam reduzidas em 60% as aliquotas do IBS e da CBS incidentes sobre os insumos "
          + "agropecuarios");

  private RotaLegislacao comResgate() {
    return rota(true, 0.7, true, true, false);
  }

  @Test
  @DisplayName("resgate: número fora da cópia e juiz SUSTENTA, a resposta sai (APROVADA_RESGATE_JUIZ)")
  void resgateAceito() {
    when(busca.buscar(anyString(), any())).thenReturn(List.of(INSUMOS));
    chatModel.responderEmSequencia(ChatModelSimulado.texto(NUMERO_FORA_DA_COPIA),
        ChatModelSimulado.texto("{\"vereditos\": [\"SUSTENTA\"]}"));

    ResultadoRota r = comResgate().responder("A reducao dos insumos vale ate 2032?", contexto);

    assertThat(r.desfecho()).isEqualTo(DesfechoInteracao.RESPONDIDA);
    assertThat(r.texto()).startsWith("A reducao de 60% das aliquotas dos insumos agropecuarios vale "
        + "ate 2032 (LC 214/2025, art. 138).");
    // Uma chamada do juiz so, com o dispositivo inteiro.
    assertThat(chatModel.promptsRecebidos()).hasSize(2);
    assertThat(chatModel.promptsRecebidos().get(1).getUserMessage().getText())
        .contains("Passagem: " + CAPUT);
    assertThat(contexto.fidelidadeLei()).isEqualTo(DesfechoFidelidadeLei.APROVADA_RESGATE_JUIZ);
    assertThat(contexto.juizLei()).isNotNull();
    assertThat(contexto.trechosCitados()).containsExactly("id-1");
  }

  @Test
  @DisplayName("resgate: veto, contradição, falha ou corte do juiz mantêm o motivo da camada 1")
  void resgateNegado() {
    when(busca.buscar(anyString(), any())).thenReturn(List.of(INSUMOS));
    for (String juiz : List.of("{\"vereditos\": [\"NAO_SUSTENTA\"]}",
        "{\"vereditos\": [\"CONTRADIZ\"]}", "{\"vereditos\": []}", "sem json")) {
      chatModel.responderEmSequencia(ChatModelSimulado.texto(NUMERO_FORA_DA_COPIA),
          ChatModelSimulado.texto(juiz));
      ContextoAssistente c = new ContextoAssistente(UUID.randomUUID(), HOJE);

      ResultadoRota r = comResgate().responder("A reducao vale ate 2032?", c);

      assertThat(r.desfecho()).as(juiz).isEqualTo(DesfechoInteracao.FIDELIDADE_REPROVADA);
      assertThat(r.texto()).isEqualTo(RotaLegislacao.TRECHOS_SEM_CONFIRMACAO);
      assertThat(c.fidelidadeLei()).as(juiz).isEqualTo(DesfechoFidelidadeLei.REPROVADA_NUMERO);
      assertThat(c.trechosCitados()).containsExactly("id-1");
      assertThat(c.juizLei()).isNotNull();
    }
  }

  @Test
  @DisplayName("resgate: polaridade vai ao juiz, numa chamada só, e o veto mantém o motivo")
  void resgateDePolaridade() {
    when(busca.buscar(anyString(), any())).thenReturn(List.of(INSUMOS));
    chatModel.responderEmSequencia(ChatModelSimulado.texto(NEGA_SEM_A_COPIA),
        ChatModelSimulado.texto("{\"vereditos\": [\"CONTRADIZ\"]}"));

    ResultadoRota r = comResgate().responder("Qual a reducao dos insumos?", contexto);

    assertThat(r.desfecho()).isEqualTo(DesfechoInteracao.FIDELIDADE_REPROVADA);
    assertThat(contexto.fidelidadeLei()).isEqualTo(DesfechoFidelidadeLei.REPROVADA_POLARIDADE);
    assertThat(chatModel.promptsRecebidos()).hasSize(2);
  }

  @Test
  @DisplayName("resgate sem o juiz não tem efeito; com o juiz e sem resgate, a camada 1 decide")
  void resgateSoComJuiz() {
    when(busca.buscar(anyString(), any())).thenReturn(List.of(INSUMOS));
    chatModel.responderTexto(NUMERO_FORA_DA_COPIA);
    assertThat(rota(true, 0.7, false, true, true).responder("Vale ate 2032?", contexto).desfecho())
        .isEqualTo(DesfechoInteracao.FIDELIDADE_REPROVADA);
    assertThat(chatModel.promptsRecebidos()).hasSize(1);
    assertThat(contexto.fidelidadeLei()).isEqualTo(DesfechoFidelidadeLei.REPROVADA_NUMERO);

    chatModel.responderTexto(NUMERO_FORA_DA_COPIA);
    ContextoAssistente outro = new ContextoAssistente(UUID.randomUUID(), HOJE);
    assertThat(comJuiz().responder("Vale ate 2032?", outro).desfecho())
        .isEqualTo(DesfechoInteracao.FIDELIDADE_REPROVADA);
    assertThat(outro.juizLei()).isNull();
  }

  @Test
  @DisplayName("resgate: cobertura só com resgate.cobertura; formato nunca vai ao juiz")
  void resgateDeCoberturaEEstruturais() {
    when(busca.buscar(anyString(), any())).thenReturn(List.of(INSUMOS));
    // Uma palavra de conteudo coberta em seis: abaixo do piso de 0,3 com o juiz.
    String poucaCobertura = json("Fertilizantes, defensivos, sementes, mudas e corretivos sao "
        + "insumos.", "T1", "Ficam reduzidas em 60% as aliquotas do IBS e da CBS incidentes sobre "
        + "os insumos agropecuarios");
    chatModel.responderTexto(poucaCobertura);
    assertThat(comResgate().responder("Insumos?", contexto).desfecho())
        .isEqualTo(DesfechoInteracao.FIDELIDADE_REPROVADA);
    assertThat(contexto.fidelidadeLei()).isEqualTo(DesfechoFidelidadeLei.REPROVADA_COBERTURA);
    assertThat(contexto.juizLei()).isNull();

    chatModel.responderEmSequencia(ChatModelSimulado.texto(poucaCobertura),
        ChatModelSimulado.texto("{\"vereditos\": [\"SUSTENTA\"]}"));
    ContextoAssistente outro = new ContextoAssistente(UUID.randomUUID(), HOJE);
    assertThat(rota(true, 0.7, true, true, true).responder("Insumos?", outro).desfecho())
        .isEqualTo(DesfechoInteracao.RESPONDIDA);
    assertThat(outro.fidelidadeLei()).isEqualTo(DesfechoFidelidadeLei.APROVADA_RESGATE_JUIZ);

    chatModel.responderTexto("texto fora do formato");
    ContextoAssistente formato = new ContextoAssistente(UUID.randomUUID(), HOJE);
    assertThat(rota(true, 0.7, true, true, true).responder("Insumos?", formato).desfecho())
        .isEqualTo(DesfechoInteracao.FIDELIDADE_REPROVADA);
    assertThat(formato.fidelidadeLei()).isEqualTo(DesfechoFidelidadeLei.REPROVADA_FORMATO);
    assertThat(formato.juizLei()).isNull();
  }
  @Test
  @DisplayName("resgate: descartada por outra conferência em código antes do juiz, sem chamá-lo")
  void resgateDescartadoAntesDoJuiz() {
    when(busca.buscar(anyString(), any())).thenReturn(List.of(INSUMOS));
    // 2032 nao esta no trecho nem na pergunta: a camada 1 manda resgatar, o ValidadorNumeros
    // descarta antes do juiz.
    chatModel.responderTexto(NUMERO_FORA_DA_COPIA);

    ResultadoRota r = comResgate().responder("Ate quando vale a reducao?", contexto);

    assertThat(r.desfecho()).isEqualTo(DesfechoInteracao.NUMERO_SEM_FONTE);
    assertThat(contexto.fidelidadeLei()).isEqualTo(DesfechoFidelidadeLei.REPROVADA_NUMERO);
    assertThat(chatModel.promptsRecebidos()).hasSize(1);
    assertThat(contexto.juizLei()).isNull();
  }
  @Test
  @DisplayName("resgate padrão: polaridade fica de fora, reprova na camada 1 sem chamar o juiz")
  void resgatePadraoSemPolaridade() {
    when(busca.buscar(anyString(), any())).thenReturn(List.of(INSUMOS));
    chatModel.responderTexto(NEGA_SEM_A_COPIA);

    ResultadoRota r = rota(true, 0.7, true, true, false, false)
        .responder("Qual a reducao dos insumos?", contexto);

    assertThat(r.desfecho()).isEqualTo(DesfechoInteracao.FIDELIDADE_REPROVADA);
    assertThat(contexto.fidelidadeLei()).isEqualTo(DesfechoFidelidadeLei.REPROVADA_POLARIDADE);
    assertThat(chatModel.promptsRecebidos()).hasSize(1);
    assertThat(contexto.juizLei()).isNull();
  }
  /** Só resgate: juiz ligado, sem veto, resgate por número (e polaridade, se pedido). */
  private RotaLegislacao soResgate(boolean polaridade) {
    return rota(true, 0.7, true, false, true, polaridade, false);
  }

  @Test
  @DisplayName("só resgate: a resposta aprovada pela camada 1 sai sem chamar o juiz")
  void soResgateAprovadaSemJuiz() {
    when(busca.buscar(anyString(), any())).thenReturn(List.of(INSUMOS));
    chatModel.responderTexto(SUSTENTADA);

    ResultadoRota r = soResgate(false).responder("Qual a reducao dos insumos?", contexto);

    assertThat(r.desfecho()).isEqualTo(DesfechoInteracao.RESPONDIDA);
    assertThat(contexto.fidelidadeLei()).isEqualTo(DesfechoFidelidadeLei.APROVADA);
    assertThat(chatModel.promptsRecebidos()).hasSize(1);
    assertThat(contexto.juizLei()).isNull();
  }

  @Test
  @DisplayName("só resgate: a resposta a resgatar chama o juiz; SUSTENTA aceita, veto mantém o motivo")
  void soResgateChamaOJuizNoResgate() {
    when(busca.buscar(anyString(), any())).thenReturn(List.of(INSUMOS));
    chatModel.responderEmSequencia(ChatModelSimulado.texto(NUMERO_FORA_DA_COPIA),
        ChatModelSimulado.texto("{\"vereditos\": [\"SUSTENTA\"]}"));

    ResultadoRota r = soResgate(false).responder("A reducao vale ate 2032?", contexto);

    assertThat(r.desfecho()).isEqualTo(DesfechoInteracao.RESPONDIDA);
    assertThat(contexto.fidelidadeLei()).isEqualTo(DesfechoFidelidadeLei.APROVADA_RESGATE_JUIZ);
    assertThat(chatModel.promptsRecebidos()).hasSize(2);

    chatModel.responderEmSequencia(ChatModelSimulado.texto(NEGA_SEM_A_COPIA),
        ChatModelSimulado.texto("{\"vereditos\": [\"CONTRADIZ\"]}"));
    ContextoAssistente outro = new ContextoAssistente(UUID.randomUUID(), HOJE);
    assertThat(soResgate(true).responder("Qual a reducao?", outro).desfecho())
        .isEqualTo(DesfechoInteracao.FIDELIDADE_REPROVADA);
    assertThat(outro.fidelidadeLei()).isEqualTo(DesfechoFidelidadeLei.REPROVADA_POLARIDADE);
    assertThat(outro.juizLei()).isNotNull();
  }

  @Test
  @DisplayName("só resgate: a cobertura volta a 0,7, como na camada 1 sozinha; com veto, 0,3")
  void soResgateCoberturaDaCamada1() {
    when(busca.buscar(anyString(), any())).thenReturn(List.of(INSUMOS));
    // "fertilizantes" e "defensivos" fora da copia: 4 de 6 palavras de conteudo (0,67).
    String parafrase = "{\"frases\": [{\"frase\": \"Fertilizantes e defensivos sao insumos "
        + "agropecuarios com aliquotas reduzidas.\", \"evidencias\": [{\"trecho\": \"T1\", "
        + "\"copia\": \"Ficam reduzidas em 60% as aliquotas do IBS e da CBS incidentes sobre os "
        + "insumos agropecuarios\"}]}]}";
    chatModel.responderTexto(parafrase);

    ResultadoRota r = soResgate(true).responder("Insumos?", contexto);

    assertThat(r.desfecho()).isEqualTo(DesfechoInteracao.FIDELIDADE_REPROVADA);
    assertThat(contexto.fidelidadeLei()).isEqualTo(DesfechoFidelidadeLei.REPROVADA_COBERTURA);
    assertThat(chatModel.promptsRecebidos()).hasSize(1);
    assertThat(contexto.juizLei()).isNull();

    // Com o veto (o padrao), a mesma parafrase passa da camada 1 e vai ao juiz.
    chatModel.responderEmSequencia(ChatModelSimulado.texto(parafrase),
        ChatModelSimulado.texto("{\"vereditos\": [\"SUSTENTA\"]}"));
    ContextoAssistente comVeto = new ContextoAssistente(UUID.randomUUID(), HOJE);
    assertThat(comJuiz().responder("Insumos?", comVeto).desfecho())
        .isEqualTo(DesfechoInteracao.RESPONDIDA);
    assertThat(comVeto.juizLei()).isNotNull();
  }

  @Test
  @DisplayName("sem veto e sem resgate: o juiz nunca roda (igual à camada 1 sozinha, cobertura 0,7)")
  void semVetoSemResgateEhACamada1() {
    when(busca.buscar(anyString(), any())).thenReturn(List.of(INSUMOS));
    chatModel.responderTexto(SUSTENTADA);
    assertThat(rota(true, 0.7, true, false, false, false, false)
        .responder("Qual a reducao dos insumos?", contexto).desfecho())
        .isEqualTo(DesfechoInteracao.RESPONDIDA);
    assertThat(chatModel.promptsRecebidos()).hasSize(1);
    assertThat(contexto.juizLei()).isNull();
  }
  @Test
  @DisplayName("só resgate com resgate.cobertura: a faixa entre 0,3 e 0,7 vai ao juiz, não passa direto")
  void soResgateResgataCobertura() {
    when(busca.buscar(anyString(), any())).thenReturn(List.of(INSUMOS));
    // 4 de 6 palavras de conteudo (0,67): abaixo de 0,7, acima de 0,3.
    String parafrase = "{\"frases\": [{\"frase\": \"Fertilizantes e defensivos sao insumos "
        + "agropecuarios com aliquotas reduzidas.\", \"evidencias\": [{\"trecho\": \"T1\", "
        + "\"copia\": \"Ficam reduzidas em 60% as aliquotas do IBS e da CBS incidentes sobre os "
        + "insumos agropecuarios\"}]}]}";
    chatModel.responderEmSequencia(ChatModelSimulado.texto(parafrase),
        ChatModelSimulado.texto("{\"vereditos\": [\"NAO_SUSTENTA\"]}"));

    ResultadoRota r = rota(true, 0.7, true, false, true, false, true)
        .responder("Insumos?", contexto);

    assertThat(r.desfecho()).isEqualTo(DesfechoInteracao.FIDELIDADE_REPROVADA);
    assertThat(contexto.fidelidadeLei()).isEqualTo(DesfechoFidelidadeLei.REPROVADA_COBERTURA);
    assertThat(chatModel.promptsRecebidos()).hasSize(2);
  }
}
