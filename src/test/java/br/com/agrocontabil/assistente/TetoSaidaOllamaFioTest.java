package br.com.agrocontabil.assistente;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.assistente.ferramentas.FerramentasDadosProdutor;
import br.com.agrocontabil.assistente.ferramentas.ProcessadorErroFerramenta;
import br.com.agrocontabil.assistente.orquestracao.ClassificacaoIntencao;
import br.com.agrocontabil.assistente.orquestracao.Intencao;
import br.com.agrocontabil.assistente.orquestracao.LlmClassificadorIntencao;
import br.com.agrocontabil.assistente.porta.DadosProdutorPorta;
import br.com.agrocontabil.assistente.porta.TratamentoProdutoPorta;
import br.com.agrocontabil.assistente.rag.BuscaLegislacaoService;
import br.com.agrocontabil.assistente.rag.TrechoRecuperado;
import br.com.agrocontabil.assistente.rag.ValidadorCitacao;
import br.com.agrocontabil.config.AssistenteProperties;
import br.com.agrocontabil.model.DesfechoInteracao;
import br.com.agrocontabil.support.OllamaHttpSimulado;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * Teto de tokens por tipo de chamada (#270), verificado no fio com o {@code OllamaChatModel} real:
 * cada chamada leva o seu {@code num_predict}, sem perder as opções padrão (modelo, thinking). E a
 * resposta cortada pelo teto ({@code done_reason: "length"}) nunca sai como completa: os testes de
 * controle mostram que, sem olhar o motivo do fim, o mesmo texto cortado passaria nos validadores.
 */
class TetoSaidaOllamaFioTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final LocalDate HOJE = LocalDate.of(2026, 11, 5);
  private static final String CORTADA = "length";

  private OllamaHttpSimulado ollama;
  private AssistenteProperties propriedades;

  @BeforeEach
  void setUp() throws IOException {
    ollama = OllamaHttpSimulado.iniciar();
    propriedades = new AssistenteProperties();
    propriedades.getTetos().setFuncoes(300);
    propriedades.getTetos().setClassificacao(200);
    propriedades.getTetos().setLegislacao(700);
  }

  @AfterEach
  void tearDown() {
    ollama.close();
  }

  @Test
  @DisplayName("Cada chamada manda o proprio num_predict e preserva modelo e thinking")
  void cadaChamadaComOProprioTeto() throws IOException {
    ollama.responder(OllamaHttpSimulado.resposta("", null));
    ollama.responder(OllamaHttpSimulado.resposta(
        "{\\\"intencao\\\": \\\"DADOS\\\", \\\"confianca\\\": 0.9}", null));
    ollama.responder(OllamaHttpSimulado.resposta("Os insumos tem reducao [T1].", null));

    rotaDados(mock(DadosProdutorPorta.class)).responder("Qual meu saldo?", contexto());
    new LlmClassificadorIntencao(ollama.modelo(), JSON, propriedades)
        .classificar("Quanto paguei de CBS?", contexto());
    rotaLegislacao().responder("Insumo tem reducao?", contexto());

    assertThat(numPredict(0)).isEqualTo(300);
    assertThat(numPredict(1)).isEqualTo(200);
    assertThat(numPredict(2)).isEqualTo(700);
    for (String corpo : ollama.corpos()) {
      JsonNode json = JSON.readTree(corpo);
      assertThat(json.get("model").asText()).isEqualTo(OllamaHttpSimulado.MODELO);
      assertThat(json.get("think").asBoolean(true)).isFalse();
      // A opcao da chamada nao apaga o contexto nem a temperatura padrao (mescla do Spring AI).
      assertThat(json.get("options").get("num_ctx").asInt()).isEqualTo(OllamaHttpSimulado.NUM_CTX);
      assertThat(json.get("options").get("temperature").asDouble())
          .isEqualTo(OllamaHttpSimulado.TEMPERATURA);
    }
  }

  @Test
  @DisplayName("Dados cortada no teto: nenhuma funcao executa, mesmo com chamada reconhecida")
  void dadosCortadaNaoExecuta() {
    DadosProdutorPorta dados = mock(DadosProdutorPorta.class);
    ollama.responder(OllamaHttpSimulado.resposta("", "{\"function\":{\"name\":"
        + "\"consultarApuracao\",\"arguments\":{\"mes\":\"2026-07\"}}}", CORTADA));

    ResultadoRota resultado = rotaDados(dados).responder("Saldo de julho e notas?", contexto());

    assertThat(resultado.desfecho()).isEqualTo(DesfechoInteracao.RESPOSTA_CORTADA);
    assertThat(resultado.aceita()).isFalse();
    assertThat(resultado.texto()).isEqualTo(RotaDados.MENSAGEM_SEM_FUNCAO);
    // A primeira de duas chamadas sairia como a resposta inteira: nada roda nos dados.
    verifyNoInteractions(dados);
  }

  @Test
  @DisplayName("Lei cortada no teto com a marca antes do corte: o texto nao sai")
  void leiCortadaNaoSai() {
    String cortado = "Os insumos agropecuarios tem reducao de aliquota [T1]. Alem disso, o";
    ollama.responder(OllamaHttpSimulado.resposta(cortado, null, CORTADA));

    ResultadoRota resultado = rotaLegislacao().responder("Insumo tem reducao?", contexto());

    assertThat(resultado.desfecho()).isEqualTo(DesfechoInteracao.RESPOSTA_CORTADA);
    assertThat(resultado.texto()).isEqualTo(RotaLegislacao.TRECHOS_SEM_CONFIRMACAO)
        .doesNotContain("Alem disso");
    // Os trechos mais proximos ficam para consulta, sem afirmar que respondem.
    assertThat(resultado.soTrechos()).isTrue();
  }

  @Test
  @DisplayName("Controle: o mesmo texto cortado, sem o motivo do fim, passaria no ValidadorCitacao")
  void controleTextoCortadoPassariaNosValidadores() {
    String cortado = "Os insumos agropecuarios tem reducao de aliquota [T1]. Alem disso, o";
    ollama.responder(OllamaHttpSimulado.resposta(cortado, null, "stop"));

    ResultadoRota resultado = rotaLegislacao().responder("Insumo tem reducao?", contexto());

    // Por isso a deteccao e pelo done_reason, nao pelo texto.
    assertThat(resultado.desfecho()).isEqualTo(DesfechoInteracao.RESPONDIDA);
    assertThat(resultado.texto()).contains("Alem disso");
  }

  @Test
  @DisplayName("Classificacao: JSON inteiro antes do corte vale (o modelo seguiu escrevendo)")
  void classificacaoComJsonInteiroAntesDoCorteVale() {
    ollama.responder(OllamaHttpSimulado.resposta("{\\\"intencao\\\": \\\"LEGISLACAO\\\", "
        + "\\\"confianca\\\": 0.9} Explicacao: a pergunta trata da lei e", null, CORTADA));
    ContextoAssistente contexto = contexto();

    ClassificacaoIntencao classificacao =
        new LlmClassificadorIntencao(ollama.modelo(), JSON, propriedades)
            .classificar("O que diz a lei sobre insumos?", contexto);

    assertThat(classificacao.intencao()).isEqualTo(Intencao.LEGISLACAO);
    assertThat(contexto.classificacaoCortada()).isFalse();
  }

  @Test
  @DisplayName("Classificacao cortada dentro do JSON: indeterminada e marcada como cortada")
  void classificacaoCortadaDentroDoJson() {
    ollama.responder(OllamaHttpSimulado.resposta("{\\\"intencao\\\": \\\"MISTO\\\", "
        + "\\\"confianca\\\": 0.9, \\\"perguntaLei\\\": \\\"insumo da direito a", null, CORTADA));
    ContextoAssistente contexto = contexto();

    ClassificacaoIntencao classificacao =
        new LlmClassificadorIntencao(ollama.modelo(), JSON, propriedades)
            .classificar("Tenho credito nessas compras?", contexto);

    assertThat(classificacao.intencao()).isEqualTo(Intencao.FORA_DE_ESCOPO);
    assertThat(classificacao.confianca()).isZero();
    // O orquestrador audita a pergunta como RESPOSTA_CORTADA, nao como fora de escopo comum.
    assertThat(contexto.classificacaoCortada()).isTrue();
  }

  @Test
  @DisplayName("Sem done_reason, tokens gerados no teto tambem contam como corte")
  void semMotivoDoFimContaPelosTokens() {
    String cortado = "Os insumos agropecuarios tem reducao de aliquota [T1]. Alem disso, o";
    ollama.responder(OllamaHttpSimulado.resposta(cortado, null, "", 700));

    ResultadoRota resultado = rotaLegislacao().responder("Insumo tem reducao?", contexto());

    assertThat(resultado.desfecho()).isEqualTo(DesfechoInteracao.RESPOSTA_CORTADA);
    assertThat(resultado.texto()).doesNotContain("Alem disso");
  }

  @Test
  @DisplayName("Classificacao com JSON cortado no meio, mesmo sem o motivo do fim: indeterminada")
  void classificacaoJsonCortadoIndeterminada() {
    ollama.responder(OllamaHttpSimulado.resposta(
        "{\\\"intencao\\\": \\\"MIS", null, "stop"));
    LlmClassificadorIntencao classificador =
        new LlmClassificadorIntencao(ollama.modelo(), JSON, propriedades);

    ClassificacaoIntencao classificacao =
        classificador.classificar("Tenho credito nessas compras?", contexto());

    assertThat(classificacao.confianca()).isZero();
  }

  private int numPredict(int indice) throws IOException {
    JsonNode corpo = JSON.readTree(ollama.corpos().get(indice));
    return corpo.get("options").get("num_predict").asInt();
  }

  private ContextoAssistente contexto() {
    return new ContextoAssistente(UUID.randomUUID(), HOJE);
  }

  private RotaDados rotaDados(DadosProdutorPorta dados) {
    return new RotaDados(ollama.modelo(), new ValidadorNumeros(), new VerificadorNegativaDados(),
        new FerramentasDadosProdutor(dados, mock(TratamentoProdutoPorta.class)),
        new ProcessadorErroFerramenta(), propriedades,
        new StaticListableBeanFactory().getBeanProvider(ObservationRegistry.class));
  }

  private RotaLegislacao rotaLegislacao() {
    BuscaLegislacaoService busca = mock(BuscaLegislacaoService.class);
    when(busca.buscar(anyString(), any())).thenReturn(List.of(new TrechoRecuperado("T1", "id-1",
        "LC 214/2025", "art. 138", "https://exemplo",
        "Os insumos agropecuarios tem reducao de aliquota.", 0.9)));
    return new RotaLegislacao(ollama.modelo(), busca, new ValidadorCitacao(),
        new ValidadorNumeros(), new VerificadorAfirmacaoDados(), propriedades);
  }
}
