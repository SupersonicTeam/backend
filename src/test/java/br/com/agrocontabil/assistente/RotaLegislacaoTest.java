package br.com.agrocontabil.assistente;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.assistente.llm.ModeloLinguagem;
import br.com.agrocontabil.assistente.rag.BuscaLegislacaoService;
import br.com.agrocontabil.assistente.rag.CitacaoLegal;
import br.com.agrocontabil.assistente.rag.TrechoRecuperado;
import br.com.agrocontabil.assistente.rag.ValidadorCitacao;
import br.com.agrocontabil.config.AssistenteProperties;
import br.com.agrocontabil.model.DesfechoInteracao;
import br.com.agrocontabil.support.ChatModelSimulado;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * Testes da {@link RotaLegislacao} (issue #156) com o modelo simulado: citação válida vira
 * referência com link; citação inventada, resposta sem marca e número fora dos trechos são
 * descartados; sem trecho, o modelo nem é chamado.
 */
class RotaLegislacaoTest {

  private static final String URL = "https://www.planalto.gov.br/ccivil_03/leis/lcp/lcp214.htm";
  private static final TrechoRecuperado TRECHO = new TrechoRecuperado("T1", "id-1", "LC 214/2025",
      "art. 138, § 1º", URL, "Ficam reduzidas em 60% as aliquotas dos insumos agropecuarios.", 0.9);

  private BuscaLegislacaoService busca;
  private ChatModelSimulado chatModel;
  private RotaLegislacao rota;
  private ContextoAssistente contexto;

  @BeforeEach
  void setUp() {
    busca = mock(BuscaLegislacaoService.class);
    chatModel = new ChatModelSimulado();
    StaticListableBeanFactory beans = new StaticListableBeanFactory();
    beans.addBean("chatModel", chatModel);
    rota = new RotaLegislacao(
        new ModeloLinguagem(beans.getBeanProvider(ChatModel.class), "ollama",
            new br.com.agrocontabil.assistente.llm.DisponibilidadeModelo(
                java.time.Duration.ofSeconds(30), System::nanoTime)),
        busca, new ValidadorCitacao(), new ValidadorNumeros(), new VerificadorAfirmacaoDados(),
        new AssistenteProperties());
    contexto = new ContextoAssistente(UUID.randomUUID(), LocalDate.of(2026, 9, 29));
  }

  @Test
  @DisplayName("#170: MISTO, lei afirmando sobre as compras do produtor: so os trechos citados")
  void regraGeralFalandoDosDadosViraTrechos() {
    when(busca.buscar(anyString(), any())).thenReturn(List.of(TRECHO));
    // O caso do PR #168: a parte de lei "viu" compras que so a parte de dados poderia ver.
    chatModel.responderTexto("Sim, os fertilizantes que você comprou têm redução de 60% [T1].");

    ResultadoRota r = rota.responderRegraGeral(
        "Os fertilizantes que comprei tem reducao?", contexto, List.of());

    assertThat(r.desfecho()).isEqualTo(DesfechoInteracao.AFIRMACAO_SOBRE_DADOS);
    assertThat(r.texto()).isEqualTo(RotaLegislacao.SO_TRECHOS).doesNotContainPattern("\\d");
    assertThat(r.citacoes()).singleElement()
        .satisfies(c -> assertThat(c.dispositivo()).isEqualTo("art. 138, § 1º"));
    String sistema = chatModel.promptsRecebidos().get(0).getSystemMessage().getText();
    assertThat(sistema).contains("SOMENTE a regra geral");
  }

  @Test
  @DisplayName("#170: MISTO com a regra em terceira pessoa passa inteira")
  void regraGeralImpessoalPassa() {
    when(busca.buscar(anyString(), any())).thenReturn(List.of(TRECHO));
    chatModel.responderTexto("Os insumos agropecuarios tem reducao de 60% [T1].");

    ResultadoRota r = rota.responderRegraGeral(
        "Os fertilizantes que comprei tem reducao?", contexto, List.of());

    assertThat(r.aceita()).isTrue();
    assertThat(r.texto()).startsWith("Os insumos agropecuarios tem reducao de 60%");
  }

  @Test
  @DisplayName("Pergunta legal separada: o 'Sim,' inicial sai e a regra geral passa")
  void perguntaSeparadaTiraSimInicial() {
    when(busca.buscar(anyString(), any())).thenReturn(List.of(TRECHO));
    chatModel.responderTexto("Sim, os insumos agropecuarios tem reducao de 60% [T1].");

    ResultadoRota r = rota.responderRegraGeral(
        "Fertilizante tem reducao de aliquota?", contexto, List.of(), true);

    assertThat(r.desfecho()).isEqualTo(DesfechoInteracao.RESPONDIDA);
    assertThat(r.texto()).startsWith("Os insumos agropecuarios tem reducao de 60%");
  }

  @Test
  @DisplayName("Pergunta inteira: o 'Sim,' responde ao caso do produtor e o texto sai (#170)")
  void perguntaInteiraMantemDescarteDoSim() {
    when(busca.buscar(anyString(), any())).thenReturn(List.of(TRECHO));
    chatModel.responderTexto("Sim, os insumos agropecuarios tem reducao de 60% [T1].");

    ResultadoRota r = rota.responderRegraGeral(
        "Quanto comprei de fertilizante e tem reducao?", contexto, List.of(), false);

    assertThat(r.desfecho()).isEqualTo(DesfechoInteracao.AFIRMACAO_SOBRE_DADOS);
  }

  @Test
  @DisplayName("Pergunta separada: tirar o 'Sim' nao salva texto que fala do que ele comprou")
  void perguntaSeparadaContinuaBarrandoDados() {
    when(busca.buscar(anyString(), any())).thenReturn(List.of(TRECHO));
    chatModel.responderTexto("Sim, os fertilizantes que você comprou têm redução de 60% [T1].");

    ResultadoRota r = rota.responderRegraGeral(
        "Fertilizante tem reducao?", contexto, List.of(), true);

    assertThat(r.desfecho()).isEqualTo(DesfechoInteracao.AFIRMACAO_SOBRE_DADOS);
  }

  @Test
  @DisplayName("Corte do 'Sim'/'Nao' inicial: so a palavra de resposta, nunca a regra")
  void semSimOuNaoInicial() {
    assertThat(RotaLegislacao.semSimOuNaoInicial("Sim, os insumos tem reducao."))
        .isEqualTo("Os insumos tem reducao.");
    assertThat(RotaLegislacao.semSimOuNaoInicial("**Sim.** É reduzida em 60%."))
        .isEqualTo("É reduzida em 60%.");
    assertThat(RotaLegislacao.semSimOuNaoInicial("Sim os insumos tem reducao."))
        .isEqualTo("Os insumos tem reducao.");
    assertThat(RotaLegislacao.semSimOuNaoInicial("NÃO, a soja em grãos não está no Anexo IX."))
        .isEqualTo("A soja em grãos não está no Anexo IX.");
    // "Nao incide" e regra, nao resposta; "Simples Nacional" nao e "Sim".
    assertThat(RotaLegislacao.semSimOuNaoInicial("Não incide IBS na exportação."))
        .isEqualTo("Não incide IBS na exportação.");
    assertThat(RotaLegislacao.semSimOuNaoInicial("Simples Nacional tem regra propria."))
        .isEqualTo("Simples Nacional tem regra propria.");
    // Negrito do Markdown e travessao, como o qwen escreve (achado do review do PR #197).
    assertThat(RotaLegislacao.semSimOuNaoInicial("**Sim**, os insumos tem reducao."))
        .isEqualTo("Os insumos tem reducao.");
    assertThat(RotaLegislacao.semSimOuNaoInicial("**Não**, a soja não está no Anexo IX."))
        .isEqualTo("A soja não está no Anexo IX.");
    assertThat(RotaLegislacao.semSimOuNaoInicial("Sim — os insumos tem reducao."))
        .isEqualTo("Os insumos tem reducao.");
    // Nada depois: fica como veio, e o verificador descarta.
    assertThat(RotaLegislacao.semSimOuNaoInicial("Sim.")).isEqualTo("Sim.");
    assertThat(RotaLegislacao.semSimOuNaoInicial("**Sim**")).isEqualTo("**Sim**");
  }

  @Test
  @DisplayName("#170: item de anexo da compra entra depois dos trechos da busca, renumerado")
  void complementaresEntramRenumerados() {
    TrechoRecuperado anexo = new TrechoRecuperado("T1", "id-anexo", "LC 214/2025",
        "Anexo IX, item 4", URL, "ANEXO IX. Item 4: Adubos minerais - 3105.20.00", 0);
    // O mesmo trecho da busca vindo de novo pelo NCM nao se repete.
    when(busca.buscar(anyString(), any())).thenReturn(List.of(TRECHO));
    chatModel.responderTexto("Os adubos minerais do Anexo IX tem reducao de 60% [T1, T2].");

    ResultadoRota r = rota.responderRegraGeral(
        "Os adubos que comprei tem reducao?", contexto, List.of(anexo, TRECHO));

    String sistema = chatModel.promptsRecebidos().get(0).getSystemMessage().getText();
    assertThat(sistema).contains("[T1] Ficam reduzidas", "[T2] ANEXO IX. Item 4")
        .doesNotContain("[T3]");
    assertThat(r.aceita()).isTrue();
    assertThat(r.citacoes()).extracting(CitacaoLegal::dispositivo)
        .containsExactly("art. 138, § 1º", "Anexo IX, item 4");
  }

  @Test
  @DisplayName("#170: sem trecho por similaridade, o item de anexo da compra basta")
  void soComplementar() {
    TrechoRecuperado anexo = new TrechoRecuperado("T9", "id-anexo", "LC 214/2025",
        "Anexo IX, item 4", URL, "ANEXO IX. Item 4: Adubos minerais - 3105.20.00", 0);
    when(busca.buscar(anyString(), any())).thenReturn(List.of());
    chatModel.responderTexto("Os adubos minerais tem reducao [T1].");

    ResultadoRota r = rota.responderRegraGeral("Adubo tem reducao?", contexto, List.of(anexo));

    assertThat(r.aceita()).isTrue();
    assertThat(r.texto()).contains("(LC 214/2025, Anexo IX, item 4)");
  }

  @Test
  @DisplayName("#170: a rota de legislacao pura nao usa o prompt nem o filtro do MISTO")
  void legislacaoPuraNaoFiltraSegundaPessoa() {
    when(busca.buscar(anyString(), any())).thenReturn(List.of(TRECHO));
    chatModel.responderTexto("Voce, como produtor, tem reducao de 60% nos insumos [T1].");

    ResultadoRota r = rota.responder("Produtor tem reducao nos insumos?", contexto);

    assertThat(r.aceita()).isTrue();
    assertThat(chatModel.promptsRecebidos().get(0).getSystemMessage().getText())
        .doesNotContain("SOMENTE a regra geral");
  }

  @Test
  @DisplayName("DoD #156: resposta com citacao valida e link oficial; trechos na auditoria")
  void respostaComCitacaoValida() {
    when(busca.buscar(anyString(), any())).thenReturn(List.of(TRECHO));
    chatModel.responderTexto("A reducao para insumos agropecuarios e de 60% [T1].");

    ResultadoRota r = rota.responder("Qual a reducao para insumos?", contexto);

    assertThat(r.aceita()).isTrue();
    // O texto do modelo com a referencia montada; a LC da referencia ganha a linha do glossario.
    assertThat(r.texto()).startsWith(
        "A reducao para insumos agropecuarios e de 60% (LC 214/2025, art. 138, § 1º)."
            + "\n\n" + LinhaGlossario.TITULO);
    assertThat(r.citacoes()).singleElement().satisfies(c -> {
      assertThat(c.url()).isEqualTo(URL);
      assertThat(c.trecho()).isEqualTo(TRECHO.texto());
    });
    assertThat(contexto.trechosCitados()).containsExactly("id-1");
  }

  @Test
  @DisplayName("#269: sigla do glossario sem explicacao ganha a linha 'O que isso quer dizer', do "
      + "backend, sem numero nem artigo")
  void siglaSemExplicacaoGanhaLinhaDoGlossario() {
    when(busca.buscar(anyString(), any())).thenReturn(List.of(TRECHO));
    chatModel.responderTexto("O IBS e a CBS dos insumos agropecuarios tem reducao de 60% [T1].");

    ResultadoRota r = rota.responder("Qual a reducao para insumos?", contexto);

    assertThat(r.aceita()).isTrue();
    String linha = r.texto().substring(r.texto().indexOf(LinhaGlossario.TITULO));
    assertThat(linha).contains("- IBS (imposto sobre bens e serviços): ",
        "- CBS (contribuição sobre bens e serviços): ", "- LC: ").doesNotContainPattern("\\d");
    assertThat(linha).doesNotContainIgnoringCase("art.");
    // Nenhuma sigla do glossario fica sem explicacao na resposta entregue.
    assertThat(GlossarioProdutor.padrao().siglasSemExplicacao(r.texto())).isEmpty();
    // E a régua de clareza da #264 (8 siglas, sem contar a definição) também a reconhece.
    assertThat(br.com.agrocontabil.config.avaliacao.ClarezaTexto.medir(r.texto())
        .siglasSemExplicacao()).isEmpty();
    assertThat(r.citacoes()).hasSize(1);
  }

  @Test
  @DisplayName("#269: sigla ja explicada no texto do modelo nao entra na linha; sem sigla, sem linha")
  void siglaExplicadaOuAusenteNaoGanhaLinha() {
    when(busca.buscar(anyString(), any())).thenReturn(List.of(TRECHO));
    // Pergunta sem referencia de norma: a unica sigla do texto (IBS) ja vem explicada.
    chatModel.responderTexto(
        "O imposto sobre bens e serviços (IBS) dos insumos tem reducao de 60% [T1].");
    String comSigla = rota.responder("Qual a reducao?", contexto).texto();
    assertThat(comSigla).doesNotContain("- IBS (").contains("- LC: ");

    chatModel.responderTexto("A reducao dos insumos e de 60% [T1].");
    String semSigla = rota.responder("Qual a reducao dos insumos?", contexto).texto();
    // So a LC da referencia montada pelo backend.
    assertThat(semSigla).contains("- LC: ").doesNotContain("- IBS (", "- CBS (");
  }

  @Test
  @DisplayName("#269: lei com o percentual por extenso: a resposta em dígitos é aceita, não descartada")
  void percentualPorExtensoNaLei() {
    TrechoRecuperado senar = new TrechoRecuperado("T1", "id-senar", "Lei 9.528/1997", "art. 6º",
        URL, "A contribuição do empregador rural pessoa física para o SENAR é de zero vírgula dois "
        + "por cento, incidente sobre a receita bruta.", 0.9);
    when(busca.buscar(anyString(), any())).thenReturn(List.of(senar));
    chatModel.responderTexto("A contribuição ao SENAR é de 0,2% da receita bruta [T1].");

    ResultadoRota r = rota.responder("Quanto o produtor paga de contribuição ao Senar?", contexto);

    assertThat(r.desfecho()).isEqualTo(DesfechoInteracao.RESPONDIDA);
    assertThat(r.texto()).startsWith("A contribuição ao SENAR é de 0,2% da receita bruta");
  }

  @Test
  @DisplayName("#269: a parte de lei do MISTO aceita tambem leva a linha; a que fala dos dados, nao")
  void regraGeralAceitaLevaALinha() {
    when(busca.buscar(anyString(), any())).thenReturn(List.of(TRECHO));
    chatModel.responderTexto("O IBS dos insumos agropecuarios tem reducao de 60% [T1].");
    ResultadoRota aceita = rota.responderRegraGeral(
        "Fertilizante tem reducao de aliquota?", contexto, List.of(), true);
    assertThat(aceita.aceita()).isTrue();
    assertThat(aceita.texto()).contains(LinhaGlossario.TITULO,
        "- IBS (imposto sobre bens e serviços): ");

    chatModel.responderTexto("Os fertilizantes que você comprou têm IBS reduzido em 60% [T1].");
    ResultadoRota soTrechos = rota.responderRegraGeral(
        "Fertilizante tem reducao?", contexto, List.of(), true);
    assertThat(soTrechos.texto()).isEqualTo(RotaLegislacao.SO_TRECHOS);
  }

  @Test
  @DisplayName("#269: resposta descartada (numero sem fonte) segue so com trechos, sem a linha")
  void descartadaNaoGanhaLinha() {
    when(busca.buscar(anyString(), any())).thenReturn(List.of(TRECHO));
    chatModel.responderTexto("O IBS dos insumos tem reducao de 70% [T1].");

    ResultadoRota r = rota.responder("Qual a reducao para insumos?", contexto);

    assertThat(r.desfecho()).isEqualTo(DesfechoInteracao.NUMERO_SEM_FONTE);
    assertThat(r.texto()).isEqualTo(RotaLegislacao.TRECHOS_SEM_CONFIRMACAO);
  }

  @Test
  @DisplayName("O modelo ve so a marca e o texto do trecho, nunca a referencia legal")
  void modeloNaoVeReferencia() {
    when(busca.buscar(anyString(), any())).thenReturn(List.of(TRECHO));
    chatModel.responderTexto("Resposta [T1].");

    rota.responder("Qual a reducao?", contexto);

    String sistema = chatModel.promptsRecebidos().get(0).getSystemMessage().getText();
    assertThat(sistema).contains("[T1] Ficam reduzidas em 60%")
        .doesNotContain("art. 138, § 1º", "LC 214/2025");
  }

  @Test
  @DisplayName("DoD #156 + #258: marca inventada derruba o texto; os trechos certos ficam")
  void citacaoInventadaBarrada() {
    when(busca.buscar(anyString(), any())).thenReturn(List.of(TRECHO));
    chatModel.responderTexto("A reducao e de 60% [T1], e ha isencao total [T4].");

    ResultadoRota r = rota.responder("Qual a reducao?", contexto);

    // O texto do modelo (e a "isencao total" do T4 inexistente) nunca chega ao produtor.
    assertThat(r.aceita()).isFalse();
    assertThat(r.desfecho()).isEqualTo(DesfechoInteracao.CITACAO_INVALIDA);
    assertThat(r.texto()).isEqualTo(RotaLegislacao.TRECHOS_SEM_CONFIRMACAO);
    assertThat(r.citacoes()).singleElement()
        .satisfies(c -> assertThat(c.url()).isEqualTo(URL));
    assertThat(contexto.trechosCitados()).containsExactly("id-1");
    // Citados pelo modelo (#258), nao relacionados: um fundamento do backend nao os tira.
    assertThat(contexto.trechosRelacionados()).isFalse();
  }

  @Test
  @DisplayName("#343: so marcas inventadas, com trechos recuperados: trechos relacionados, nunca "
      + "'nao encontrei fundamento legal'")
  void soMarcaInventada() {
    when(busca.buscar(anyString(), any())).thenReturn(List.of(TRECHO));
    chatModel.responderTexto("Ha isencao total [T4].");

    ResultadoRota r = rota.responder("Qual a reducao?", contexto);

    assertThat(r.aceita()).isFalse();
    assertThat(r.desfecho()).isEqualTo(DesfechoInteracao.CITACAO_INVALIDA);
    assertThat(r.texto()).isEqualTo(RotaLegislacao.TRECHOS_SEM_CONFIRMACAO)
        .doesNotContain(ValidadorCitacao.SEM_FUNDAMENTO).doesNotContain("isencao");
    assertThat(r.citacoes()).extracting(CitacaoLegal::referencia)
        .containsExactly("LC 214/2025, art. 138, § 1º");
    assertThat(contexto.trechosCitados()).containsExactly("id-1");
    assertThat(contexto.trechosRelacionados()).isTrue();
  }

  @Test
  @DisplayName("#343: NAO_ENCONTREI com marca no texto (premissa falsa): o texto do modelo sai, "
      + "os trechos ficam sem afirmar que respondem, e o desfecho segue de recusa")
  void naoEncontreiComMarca() {
    when(busca.buscar(anyString(), any())).thenReturn(List.of(TRECHO));
    chatModel.responderTexto(PromptsCeres.NAO_ENCONTREI + " O trecho [T1] trata de outra coisa.");

    ResultadoRota r = rota.responder("Qual artigo isenta o celular?", contexto);

    assertThat(r.desfecho()).isEqualTo(DesfechoInteracao.CITACAO_INVALIDA);
    assertThat(r.texto()).isEqualTo(RotaLegislacao.TRECHOS_SEM_CONFIRMACAO)
        .doesNotContain("outra coisa");
    assertThat(r.citacoes()).singleElement()
        .satisfies(c -> assertThat(c.url()).isEqualTo(URL));
  }

  @Test
  @DisplayName("#343: resposta sem marca nenhuma: os tres trechos mais proximos, na ordem da busca")
  void semMarcaTresTrechosRelacionados() {
    when(busca.buscar(anyString(), any())).thenReturn(cincoTrechos());
    chatModel.responderTexto("A reforma muda bastante coisa para o produtor.");

    ResultadoRota r = rota.responder("O que muda pra mim com o IBS?", contexto);

    assertThat(r.desfecho()).isEqualTo(DesfechoInteracao.CITACAO_INVALIDA);
    assertThat(r.texto()).isEqualTo(RotaLegislacao.TRECHOS_SEM_CONFIRMACAO);
    assertThat(r.citacoes()).extracting(CitacaoLegal::dispositivo)
        .containsExactly("art. 1", "art. 2", "art. 3");
    assertThat(contexto.trechosCitados()).containsExactly("id-1", "id-2", "id-3");
    assertThat(r.soTrechos()).isTrue();
    assertThat(contexto.trechosRelacionados()).isTrue();
  }

  @Test
  @DisplayName("#343: NAO_ENCONTREI sem marca, com cinco trechos: os tres mais proximos")
  void naoEncontreiTresTrechosRelacionados() {
    when(busca.buscar(anyString(), any())).thenReturn(cincoTrechos());
    chatModel.responderTexto(PromptsCeres.NAO_ENCONTREI);

    ResultadoRota r = rota.responder("O que muda pra mim com o IBS?", contexto);

    assertThat(r.texto()).isEqualTo(RotaLegislacao.TRECHOS_SEM_CONFIRMACAO);
    assertThat(r.citacoes()).extracting(CitacaoLegal::dispositivo)
        .containsExactly("art. 1", "art. 2", "art. 3");
  }

  @Test
  @DisplayName("#260/#343: MISTO com a pergunta inteira (sem parte legal separada) e sem marca: a "
      + "lei descartada nao leva artigos para o lado dos dados")
  void mistoSemPerguntaSeparadaSemTrechosRelacionados() {
    when(busca.buscar(anyString(), any())).thenReturn(cincoTrechos());
    chatModel.responderTexto(PromptsCeres.NAO_ENCONTREI);

    ResultadoRota r = rota.responderRegraGeral("Quanto comprei e tem reducao?", contexto,
        List.of(), false);

    assertThat(r.desfecho()).isEqualTo(DesfechoInteracao.CITACAO_INVALIDA);
    assertThat(r.texto()).isEqualTo(ValidadorCitacao.SEM_FUNDAMENTO);
    assertThat(r.citacoes()).isEmpty();
    assertThat(r.soTrechos()).isFalse();
    assertThat(contexto.trechosCitados()).isEmpty();
    assertThat(contexto.trechosRelacionados()).isFalse();
  }

  @Test
  @DisplayName("#343: MISTO com a parte legal separada e sem marca: trechos relacionados")
  void mistoComPerguntaSeparadaTrechosRelacionados() {
    when(busca.buscar(anyString(), any())).thenReturn(cincoTrechos());
    chatModel.responderTexto("Depende do caso.");

    ResultadoRota r = rota.responderRegraGeral("Fertilizante tem reducao?", contexto, List.of(),
        true);

    assertThat(r.texto()).isEqualTo(RotaLegislacao.TRECHOS_SEM_CONFIRMACAO);
    assertThat(r.citacoes()).hasSize(RotaLegislacao.TRECHOS_RELACIONADOS);
    assertThat(r.soTrechos()).isTrue();
  }

  @Test
  @DisplayName("#258: no MISTO, numero sem fonte tambem vira os trechos, sem afirmar")
  void regraGeralComNumeroSemFonte() {
    when(busca.buscar(anyString(), any())).thenReturn(List.of(TRECHO));
    chatModel.responderTexto("A reducao e de 70% [T1].");

    ResultadoRota r = rota.responderRegraGeral("Tem reducao?", contexto, List.of(), false);

    assertThat(r.desfecho()).isEqualTo(DesfechoInteracao.NUMERO_SEM_FONTE);
    assertThat(r.texto()).isEqualTo(RotaLegislacao.TRECHOS_SEM_CONFIRMACAO);
    assertThat(r.soTrechos()).isTrue();
  }

  @Test
  @DisplayName("#258: numero que a lei nao diz derruba o texto, mas os trechos citados ficam")
  void numeroForaDosTrechos() {
    when(busca.buscar(anyString(), any())).thenReturn(List.of(TRECHO));
    chatModel.responderTexto("A reducao e de 70% [T1].");

    ResultadoRota r = rota.responder("Qual a reducao?", contexto);

    assertThat(r.aceita()).isFalse();
    assertThat(r.desfecho()).isEqualTo(DesfechoInteracao.NUMERO_SEM_FONTE);
    // Nem o 70% nem "nao encontrei fundamento": o produtor recebe o artigo certo.
    assertThat(r.texto()).isEqualTo(RotaLegislacao.TRECHOS_SEM_CONFIRMACAO).doesNotContain("70");
    List<CitacaoLegal> citacoes = r.citacoes();
    assertThat(citacoes).singleElement().satisfies(c -> assertThat(c.url()).isEqualTo(URL));
    assertThat(contexto.trechosCitados()).containsExactly("id-1");
  }

  @Test
  @DisplayName("#343: NAO_ENCONTREI do modelo, com trecho recuperado: trechos relacionados, nao "
      + "'nao encontrei fundamento legal' (esta frase fica so para quando nao ha trecho)")
  void modeloNaoEncontrou() {
    when(busca.buscar(anyString(), any())).thenReturn(List.of(TRECHO));
    chatModel.responderTexto(PromptsCeres.NAO_ENCONTREI);

    ResultadoRota r = rota.responder("Como declarar o imposto de renda?", contexto);

    assertThat(r.texto()).isEqualTo(RotaLegislacao.TRECHOS_SEM_CONFIRMACAO)
        .isNotEqualTo(ValidadorCitacao.SEM_FUNDAMENTO);
    assertThat(r.desfecho()).isEqualTo(DesfechoInteracao.CITACAO_INVALIDA);
  }

  @Test
  @DisplayName("Sem trecho acima do limiar: resposta fixa sem chamar o modelo")
  void semTrechoNaoChamaModelo() {
    when(busca.buscar(anyString(), any())).thenReturn(List.of());

    ResultadoRota r = rota.responder("Qual a capital da Franca?", contexto);

    assertThat(r.desfecho()).isEqualTo(DesfechoInteracao.SEM_FUNDAMENTO_LEGAL);
    assertThat(r.texto()).isEqualTo(ValidadorCitacao.SEM_FUNDAMENTO);
    assertThat(chatModel.promptsRecebidos()).isEmpty();
  }

  /** Cinco trechos, do mais para o menos próximo, como a busca devolve. */
  private static List<TrechoRecuperado> cincoTrechos() {
    return java.util.stream.IntStream.rangeClosed(1, 5)
        .mapToObj(n -> new TrechoRecuperado("T" + n, "id-" + n, "LC 214/2025", "art. " + n, URL,
            "Texto do artigo " + n + " sobre o IBS e a CBS.", 1.0 - n / 10.0))
        .toList();
  }
}
