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
 * Aviso de divergência (#584, decisão do Murillo de 2026-11-22: "quero que ela aponte o erro"):
 * quando o modelo dá o veredito de correção e as guardas que não dependem da frase de correção
 * passam, a resposta é uma frase fixa do backend dizendo que a resposta anterior não confere com o
 * texto da lei naquele ponto, mais o trecho literal com a citação. Nenhuma prosa do modelo.
 */
@ExtendWith(MockitoExtension.class)
class ReconferenciaLeiAvisoTest {

  private static final LocalDate HOJE = LocalDate.of(2026, 11, 22);
  private static final String REFERENCIA = "LC 214/2025, art. 137, § 1º";
  private static final String TEXTO_TRECHO = "Ficam reduzidas em 60% (sessenta por cento) as "
      + "alíquotas do IBS e da CBS incidentes sobre o fornecimento dos produtos agropecuários in "
      + "natura. (Redação dada pela Lei Complementar nº 227, de 2026)";
  private static final String CITACAO = "Ficam reduzidas em 60% (sessenta por cento) as "
      + "alíquotas do IBS";
  private static final String SEM_NUMERO = "alíquotas do IBS e da CBS incidentes sobre o "
      + "fornecimento dos produtos agropecuários";
  private static final TrechoRecuperado TRECHO = new TrechoRecuperado(null, "t137",
      "LC 214/2025", "art. 137, § 1º", "https://planalto", TEXTO_TRECHO, 0.9);
  /** O mesmo dispositivo, mas com um trecho que nega (sentido: não se aplica). */
  private static final TrechoRecuperado VEDADO = new TrechoRecuperado(null, "t137",
      "LC 214/2025", "art. 137, § 1º", "https://planalto",
      "Não se aplica a redução de 60% (sessenta por cento) às operações com produtos "
          + "industrializados.", 0.9);
  private static final String CITACAO_VEDADO = "Não se aplica a redução de 60% (sessenta por "
      + "cento) às operações";
  private static final TrechoRecuperado PRAZO = new TrechoRecuperado(null, "t138",
      "LC 214/2025", "art. 138", "https://planalto",
      "Fica diferido o pagamento em 30 dias contados da entrada do insumo.", 0.8);
  private static final TrechoRecuperado OUTRO = new TrechoRecuperado(null, "t99", "LC 214/2025",
      "art. 99", "u", "Outro trecho de lei sobre insumos e prazos.", 0.5);
  private static final TrechoRecuperado DE_REGIME = new TrechoRecuperado(null, "t200",
      "LC 214/2025", "art. 200", "u",
      "Ficam reduzidas em 60% (sessenta por cento) as alíquotas do regime do setor.", 0.7);

  private static final String AVISO_ESPERADO = "Reli o texto da lei em que a resposta anterior se "
      + "baseia (" + REFERENCIA + ") e a resposta não confere com ele neste ponto. Vale o texto "
      + "abaixo, com o link: leia o dispositivo. " + ReconferenciaLei.ENQUADRAMENTO;
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

  private MotivoReconferenciaLei motivo() {
    return contexto.reconferenciaLei().motivo();
  }

  @Test
  @DisplayName("Padrão: o aviso de divergência vem ligado e o 'corrijo' por texto, desligado")
  void padroes() {
    assertThat(new AssistenteProperties().getContinuidade().isLeiAvisoDeDivergencia()).isTrue();
    assertThat(new AssistenteProperties().getContinuidade().isLeiCorrijoPorTexto()).isFalse();
  }

  @Test
  @DisplayName("Número errado (40% no lugar de 60%): aviso fixo com o trecho, uma chamada, sem "
      + "prosa do modelo")
  void avisoPorNumero() {
    when(modelo.chamar(any())).thenReturn(resposta(correcao(
        "A soja tem redução de 40% da alíquota [T1]", "A soja tem redução de 60% da alíquota [T1]",
        "T1", CITACAO)));

    ReconferenciaLei.Resultado r = reconferencia.reconferir(
        parDeLei("A soja tem redução de 40% da alíquota"), contexto);

    assertThat(r.desfecho()).isEqualTo(DesfechoContinuidade.RECONFERENCIA_AVISO_DIVERGENCIA);
    assertThat(motivo()).isEqualTo(MotivoReconferenciaLei.CORRIJO_AVISO_DIVERGENCIA_NUMERO);
    assertThat(r.rota().desfecho()).isEqualTo(DesfechoInteracao.RESPONDIDA);
    assertThat(r.rota().texto()).isEqualTo(AVISO_ESPERADO)
        .doesNotContain("60").doesNotContain("40").doesNotContain("corrijo");
    assertThat(r.rota().citacoes()).singleElement()
        .satisfies(c -> assertThat(c.referencia()).isEqualTo(REFERENCIA));
    assertThat(r.modeloRespondeu()).isTrue();
    assertThat(contexto.registroTurno().reconferida()).isTrue();
    assertThat(contexto.reconferenciaLei().tokensEntradaConcordancia()).isNull();
    verify(modelo, times(1)).chamar(any());
    verify(rotaLegislacao, never()).responder(any(), any(), any(FontePergunta.class));
  }

  @Test
  @DisplayName("Sim no lugar de Não: o aviso só sai com a citação literal dando o sinal (aqui, "
      + "um trecho que nega); a frase do modelo só tem o Sim/Não lido e nada dela é mostrado")
  void avisoPorPolaridade() {
    org.mockito.Mockito.doReturn(List.of(VEDADO)).when(buscaTextual)
        .buscarDispositivos(anyList(), eq(HOJE));
    when(modelo.chamar(any())).thenReturn(resposta(correcao(
        "Sim, a redução de 60% alcança os produtos industrializados [T1]",
        "Não, a redução de 60% não alcança os produtos industrializados [T1]", "T1",
        CITACAO_VEDADO)));

    ReconferenciaLei.Resultado r = reconferencia.reconferir(
        parDeLei("Sim, a redução de 60% alcança os produtos industrializados"), contexto);

    assertThat(motivo()).isEqualTo(MotivoReconferenciaLei.CORRIJO_AVISO_DIVERGENCIA_SENTIDO);
    assertThat(r.rota().texto()).isEqualTo(AVISO_ESPERADO);
  }

  @Test
  @DisplayName("Resposta 'Sim' certa e modelo dizendo 'Não': a citação é afirmativa, sem aviso "
      + "falso (apoio neutro)")
  void polaridadeSemCorroboracaoNaoAvisa() {
    when(modelo.chamar(any())).thenReturn(resposta(correcao(
        "Sim, a soja tem redução da alíquota [T1]", "Não, a soja tem redução da alíquota [T1]",
        "T1", CITACAO)));

    ReconferenciaLei.Resultado r = reconferencia.reconferir(
        parDeLei("Sim, a soja tem redução da alíquota"), contexto);

    assertThat(motivo()).isEqualTo(MotivoReconferenciaLei.CORRIJO_SEM_DIFERENCA);
    assertThat(r.rota().texto()).isEqualTo(APOIO_NEUTRO).doesNotContain("não confere");
  }

  @Test
  @DisplayName("Resposta 'Não' errada: a citação é afirmativa e o aviso sai; com citação que "
      + "nega, não")
  void polaridadeDeNaoParaSim() {
    when(modelo.chamar(any())).thenReturn(resposta(correcao(
        "Não, as alíquotas do IBS e da CBS não ficam reduzidas [T1]",
        "Sim, as alíquotas do IBS e da CBS ficam reduzidas [T1]", "T1", CITACAO)));
    reconferencia.reconferir(
        parDeLei("Não, as alíquotas do IBS e da CBS não ficam reduzidas"), contexto);
    assertThat(motivo()).isEqualTo(MotivoReconferenciaLei.CORRIJO_AVISO_DIVERGENCIA_SENTIDO);

    contexto = new ContextoAssistente(UUID.randomUUID(), HOJE);
    org.mockito.Mockito.doReturn(List.of(VEDADO)).when(buscaTextual)
        .buscarDispositivos(anyList(), eq(HOJE));
    when(modelo.chamar(any())).thenReturn(resposta(correcao(
        "Não, as alíquotas do IBS e da CBS não ficam reduzidas [T1]",
        "Sim, as alíquotas do IBS e da CBS ficam reduzidas [T1]", "T1", CITACAO_VEDADO)));
    reconferencia.reconferir(
        parDeLei("Não, as alíquotas do IBS e da CBS não ficam reduzidas"), contexto);
    assertThat(motivo()).isEqualTo(MotivoReconferenciaLei.CORRIJO_SEM_DIFERENCA);
  }

  @Test
  @DisplayName("Resposta certa com número de outro ponto (um ano) e a citação com outro número: "
      + "há número em comum com o trecho, sem aviso")
  void numeroEmComumNaoAvisa() {
    TrechoRecuperado doisNumeros = new TrechoRecuperado(null, "t137", "LC 214/2025",
        "art. 137, § 1º", "https://planalto", "Ficam reduzidas em 60% (sessenta por cento) as "
            + "alíquotas do IBS, pagas em 30 dias contados da entrada.", 0.9);
    org.mockito.Mockito.doReturn(List.of(doisNumeros)).when(buscaTextual)
        .buscarDispositivos(anyList(), eq(HOJE));
    when(modelo.chamar(any())).thenReturn(resposta(correcao(
        "A soja tem redução de 60% a partir de 2027 [T1]",
        "A soja tem redução de 60% a partir de 2026 [T1]", "T1",
        "pagas em 30 dias contados da entrada")));

    ReconferenciaLei.Resultado r = reconferencia.reconferir(
        parDeLei("A soja tem redução de 60% a partir de 2027"), contexto);

    assertThat(motivo()).isEqualTo(MotivoReconferenciaLei.CORRIJO_SEM_DIFERENCA);
    assertThat(r.rota().texto()).isEqualTo(APOIO_NEUTRO).doesNotContain("não confere");
  }

  @Test
  @DisplayName("'Sim' certa e o trecho só restringe ('exceto'): restrição não é negação "
      + "estrita, sem aviso")
  void restricaoNaoBastaParaAvisarUmSim() {
    TrechoRecuperado comExcecao = new TrechoRecuperado(null, "t137", "LC 214/2025",
        "art. 137, § 1º", "https://planalto", "Aplica-se a redução às operações com produtos "
            + "industrializados, exceto os de uso pessoal.", 0.9);
    org.mockito.Mockito.doReturn(List.of(comExcecao)).when(buscaTextual)
        .buscarDispositivos(anyList(), eq(HOJE));
    when(modelo.chamar(any())).thenReturn(resposta(correcao(
        "Sim, a redução alcança os produtos industrializados [T1]",
        "Não, a redução não alcança os produtos industrializados [T1]", "T1",
        "Aplica-se a redução às operações com produtos industrializados")));

    reconferencia.reconferir(
        parDeLei("Sim, a redução alcança os produtos industrializados"), contexto);

    assertThat(motivo()).isEqualTo(MotivoReconferenciaLei.CORRIJO_SEM_DIFERENCA);
  }

  @Test
  @DisplayName("'Sim' e um trecho que nega, mas de outro assunto: sem palavras em comum, sem aviso")
  void outroAssuntoNaoAvisa() {
    org.mockito.Mockito.doReturn(List.of(VEDADO)).when(buscaTextual)
        .buscarDispositivos(anyList(), eq(HOJE));
    when(modelo.chamar(any())).thenReturn(resposta(correcao(
        "Sim, o regime opcional vale para cooperativas [T1]",
        "Não, o regime opcional não vale para cooperativas [T1]", "T1", CITACAO_VEDADO)));

    reconferencia.reconferir(parDeLei("Sim, o regime opcional vale para cooperativas"), contexto);

    assertThat(motivo()).isEqualTo(MotivoReconferenciaLei.CORRIJO_SEM_DIFERENCA);
  }

  @Test
  @DisplayName("A mensagem do produtor nunca vai ao modelo: o prompt leva só a pergunta e a "
      + "resposta do par, e o veredito de um 'valor errado do produtor' não tem de onde sair")
  void promptSemAMensagemDoProdutor() throws Exception {
    when(modelo.chamar(any())).thenReturn(resposta(correcao(
        "A soja tem redução de 40% da alíquota [T1]", "A soja tem redução de 60% da alíquota [T1]",
        "T1", CITACAO)));

    reconferencia.reconferir(parDeLei("A soja tem redução de 60% da alíquota"), contexto);

    org.mockito.ArgumentCaptor<org.springframework.ai.chat.prompt.Prompt> prompt =
        org.mockito.ArgumentCaptor.forClass(org.springframework.ai.chat.prompt.Prompt.class);
    verify(modelo).chamar(prompt.capture());
    assertThat(prompt.getValue().getInstructions()).hasSize(2);
    com.fasterxml.jackson.databind.JsonNode usuario = new com.fasterxml.jackson.databind
        .ObjectMapper().readTree(prompt.getValue().getInstructions().get(1).getText());
    assertThat(usuario.fieldNames()).toIterable()
        .containsExactlyInAnyOrder("pergunta", "respostaAnterior");
    assertThat(usuario.get("pergunta").asText()).isEqualTo("a soja tem redução?");
  }

  @Test
  @DisplayName("Cada motivo pertence a um grupo de veredito: aviso e barrado por guarda são "
      + "correção; confirmação, confirmação descartada; o resto, sem veredito")
  void grupoDoVeredito() {
    assertThat(MotivoReconferenciaLei.CORRIJO_AVISO_DIVERGENCIA_NUMERO.veredito())
        .isEqualTo(MotivoReconferenciaLei.Veredito.CORRECAO);
    assertThat(MotivoReconferenciaLei.CORRIJO_AVISO_DIVERGENCIA_SENTIDO.veredito())
        .isEqualTo(MotivoReconferenciaLei.Veredito.CORRECAO);
    assertThat(MotivoReconferenciaLei.CORRIJO_APOIO_LITERAL.veredito())
        .isEqualTo(MotivoReconferenciaLei.Veredito.CORRECAO);
    assertThat(MotivoReconferenciaLei.CORRIGIU.veredito())
        .isEqualTo(MotivoReconferenciaLei.Veredito.CORRECAO);
    assertThat(MotivoReconferenciaLei.CONFIRMOU.veredito())
        .isEqualTo(MotivoReconferenciaLei.Veredito.CONFIRMACAO);
    assertThat(MotivoReconferenciaLei.CONFIRMO_NUMERO_FATIA.veredito())
        .isEqualTo(MotivoReconferenciaLei.Veredito.CONFIRMACAO);
    assertThat(MotivoReconferenciaLei.NAO_SUSTENTA.veredito())
        .isEqualTo(MotivoReconferenciaLei.Veredito.NAO_SUSTENTA);
    for (MotivoReconferenciaLei m : List.of(MotivoReconferenciaLei.TEXTO_FIXO,
        MotivoReconferenciaLei.SEM_MODELO_DISJUNTOR, MotivoReconferenciaLei.CORTADA_PELO_TETO,
        MotivoReconferenciaLei.SAIDA_NAO_E_JSON, MotivoReconferenciaLei.VEREDITO_DESCONHECIDO)) {
      assertThat(m.veredito()).as(m.name()).isEqualTo(MotivoReconferenciaLei.Veredito.NENHUM);
    }
  }

  @Test
  @DisplayName("Dois dispositivos divergentes: o aviso no plural, só com os trechos dos "
      + "fundamentos")
  void avisoNoPlural() {
    org.mockito.Mockito.doReturn(List.of(TRECHO, PRAZO)).when(buscaTextual)
        .buscarDispositivos(anyList(), eq(HOJE));
    String fatia = "A soja tem redução de 40% da alíquota (" + REFERENCIA + "). O prazo é de "
        + "15 dias (LC 214/2025, art. 138).";
    ParConferido par = parComFatia(fatia);
    when(modelo.chamar(any())).thenReturn(resposta("{\"veredito\": \"CORRIJO\", \"correcoes\": ["
        + item("A soja tem redução de 40% da alíquota [T1]",
            "A soja tem redução de 60% da alíquota [T1]", "T1", CITACAO) + ", "
        + item("O prazo é de 15 dias [T2]", "O prazo é de 30 dias [T2]", "T2",
            "Fica diferido o pagamento em 30 dias contados da entrada") + "]}"));

    ReconferenciaLei.Resultado r = reconferencia.reconferir(par, contexto);

    assertThat(motivo()).isEqualTo(MotivoReconferenciaLei.CORRIJO_AVISO_DIVERGENCIA_NUMERO);
    assertThat(r.rota().texto()).isEqualTo("Reli o texto da lei nos dispositivos em que a "
        + "resposta anterior se baseia (" + REFERENCIA + " e LC 214/2025, art. 138) e a resposta "
        + "não confere com eles neste ponto. Vale o texto de cada um, abaixo, com o link: leia "
        + "os dispositivos. " + ReconferenciaLei.ENQUADRAMENTO);
    assertThat(r.rota().citacoes()).hasSize(2);
  }

  @Test
  @DisplayName("Resposta certa, modelo errado: o número da resposta tem apoio literal no trecho, "
      + "sem aviso falso (apoio neutro)")
  void semAvisoFalsoNaRespostaCerta() {
    when(modelo.chamar(any())).thenReturn(resposta(correcao(
        "A soja tem redução de 60% da alíquota [T1]", "A soja tem redução de 40% da alíquota [T1]",
        "T1", CITACAO)));

    ReconferenciaLei.Resultado r = reconferencia.reconferir(
        parDeLei("A soja tem redução de 60% da alíquota"), contexto);

    assertThat(motivo()).isEqualTo(MotivoReconferenciaLei.CORRIJO_APOIO_LITERAL);
    assertThat(r.desfecho()).isEqualTo(DesfechoContinuidade.RECONFERENCIA_DESCARTADA_APOIO_LITERAL);
    assertThat(r.rota().texto()).isEqualTo(APOIO_NEUTRO).doesNotContain("não confere");
    assertThat(r.rota().desfecho()).isEqualTo(DesfechoInteracao.RECONFERENCIA_DESCARTADA);
  }

  @Test
  @DisplayName("Resposta sem número e sem Sim/Não que mude: sem diferença verificável, apoio "
      + "neutro")
  void semDiferencaVerificavel() {
    when(modelo.chamar(any())).thenReturn(resposta(correcao(
        "Sim, a soja tem redução da alíquota [T1]",
        "Sim, a soja tem redução da alíquota [T1]", "T1", CITACAO)));

    ReconferenciaLei.Resultado r = reconferencia.reconferir(
        parDeLei("Sim, a soja tem redução da alíquota"), contexto);

    assertThat(motivo()).isEqualTo(MotivoReconferenciaLei.CORRIJO_SEM_DIFERENCA);
    assertThat(r.rota().texto()).isEqualTo(APOIO_NEUTRO);
  }

  @Test
  @DisplayName("Número da resposta sem apoio, mas a citação não traz outro número: sem "
      + "diferença verificável")
  void citacaoSemOutroNumero() {
    when(modelo.chamar(any())).thenReturn(resposta(correcao(
        "A soja tem redução de 40% da alíquota [T1]", "A soja tem outra redução [T1]", "T1",
        SEM_NUMERO)));

    reconferencia.reconferir(parDeLei("A soja tem redução de 40% da alíquota"), contexto);

    assertThat(motivo()).isEqualTo(MotivoReconferenciaLei.CORRIJO_SEM_DIFERENCA);
  }

  @Test
  @DisplayName("Guardas do trecho do veredito: formato, marca, trecho não citado, citação "
      + "literal e regime específico, um código cada")
  void guardasDoTrecho() {
    org.mockito.Mockito.doReturn(List.of(TRECHO, DE_REGIME)).when(buscaTextual)
        .buscarDispositivos(anyList(), eq(HOJE));
    when(modelo.chamar(any())).thenReturn(
        resposta("{\"veredito\": \"CORRIJO\", \"correcoes\": []}"),
        resposta("{\"veredito\": \"CORRIJO\", \"correcoes\": [{\"fraseCorrigida\": \"x\"}]}"),
        resposta(correcao("a", "b", "T9", CITACAO)),
        resposta(correcao("a", "b", "T3", CITACAO)),
        resposta(correcao("a", "b", "T1", "Redação dada pela Lei Complementar nº 227")),
        resposta(correcao("a", "b", "T2", "Ficam reduzidas em 60% (sessenta por cento) as "
            + "alíquotas do regime")));

    String fatia = "A soja tem redução de 40% da alíquota (" + REFERENCIA + ").";
    String fatiaRegime = "A soja tem redução de 40% da alíquota (" + REFERENCIA
        + "; LC 214/2025, art. 200).";
    List<MotivoReconferenciaLei> motivos = new java.util.ArrayList<>();
    for (int i = 0; i < 6; i++) {
      contexto = new ContextoAssistente(UUID.randomUUID(), HOJE);
      // O quarto trecho (T3) existe entre os do prompt mas a resposta anterior nao o citou.
      reconferencia.reconferir(parComFatia(i == 5 ? fatiaRegime : fatia), contexto);
      motivos.add(motivo());
    }

    assertThat(motivos).containsExactly(MotivoReconferenciaLei.CORRIJO_FORMATO_LISTA,
        MotivoReconferenciaLei.CORRIJO_FORMATO_ITEM,
        MotivoReconferenciaLei.CORRIJO_MARCA_INEXISTENTE,
        MotivoReconferenciaLei.CORRIJO_TRECHO_NAO_CITADO,
        MotivoReconferenciaLei.CORRIJO_CITACAO_NAO_LITERAL,
        MotivoReconferenciaLei.CORRIJO_REGIME);
  }

  @Test
  @DisplayName("Chave do aviso desligada: o apoio neutro de antes, com o motivo de veredito "
      + "indicado")
  void chaveDesligada() {
    propriedades.getContinuidade().setLeiAvisoDeDivergencia(false);
    when(modelo.chamar(any())).thenReturn(resposta(correcao(
        "A soja tem redução de 40% da alíquota [T1]", "A soja tem redução de 60% da alíquota [T1]",
        "T1", CITACAO)));

    ReconferenciaLei.Resultado r = reconferencia.reconferir(
        parDeLei("A soja tem redução de 40% da alíquota"), contexto);

    assertThat(motivo()).isEqualTo(MotivoReconferenciaLei.CORRIJO_INDICADO_APOIO);
    assertThat(r.desfecho()).isEqualTo(DesfechoContinuidade.RECONFERENCIA_APOIO_SEM_CORRECAO);
    assertThat(r.rota().texto()).isEqualTo(APOIO_NEUTRO);
  }

  @Test
  @DisplayName("A confirmação validada segue como está, com o aviso ligado")
  void confirmacaoNaoMuda() {
    when(modelo.chamar(any())).thenReturn(resposta("{\"veredito\": \"CONFIRMO\", "
        + "\"fundamentos\": [{\"marca\": \"T1\", \"citacao\": \"" + CITACAO + "\"}]}"));

    ReconferenciaLei.Resultado r = reconferencia.reconferir(
        parDeLei("Sim, a soja tem redução de 60% da alíquota"), contexto);

    assertThat(r.desfecho()).isEqualTo(DesfechoContinuidade.RECONFERENCIA_CONFIRMOU);
    assertThat(motivo()).isEqualTo(MotivoReconferenciaLei.CONFIRMOU);
  }

  @Test
  @DisplayName("Correção por texto ligada: ela vem antes do aviso (a chave de reversão)")
  void correcaoPorTextoTemPrioridade() {
    propriedades.getContinuidade().setLeiCorrijoPorTexto(true);
    when(modelo.chamar(any())).thenReturn(resposta(correcao(
        "A soja tem redução de 40% da alíquota [T1]", "A soja tem redução de 60% da alíquota [T1]",
        "T1", CITACAO)));
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

  private static String item(String anterior, String corrigida, String marca, String citacao) {
    return "{\"fraseAnterior\": \"" + anterior + "\", \"fraseCorrigida\": \"" + corrigida
        + "\", \"fundamento\": {\"marca\": \"" + marca + "\", \"citacao\": \"" + citacao + "\"}}";
  }

  private static String correcao(String anterior, String corrigida, String marca,
      String citacao) {
    return "{\"veredito\": \"CORRIJO\", \"correcoes\": [" + item(anterior, corrigida, marca,
        citacao) + "]}";
  }

  private static ChatResponse resposta(String texto) {
    return new ChatResponse(List.of(new Generation(new AssistantMessage(texto))));
  }
}
