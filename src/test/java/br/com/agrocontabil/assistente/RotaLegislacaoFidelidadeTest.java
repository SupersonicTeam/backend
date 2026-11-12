package br.com.agrocontabil.assistente;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.assistente.fidelidade.ConferenciaFidelidadeLei;
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
    AssistenteProperties propriedades = new AssistenteProperties();
    propriedades.getFidelidadeLei().setEnabled(ligada);
    propriedades.getFidelidadeLei().setCoberturaMinima(cobertura);
    return new RotaLegislacao(modelo, busca, new ValidadorCitacao(), new ValidadorNumeros(),
        new VerificadorAfirmacaoDados(), propriedades,
        new ConferenciaFidelidadeLei(GlossarioProdutor.padrao()));
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
    ResultadoRota depois = rota(false, 0.7).responder("Qual a reducao dos insumos?", outro);

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
}
