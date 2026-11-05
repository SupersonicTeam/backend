package br.com.agrocontabil.assistente;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.assistente.ferramentas.FerramentasDadosProdutor;
import br.com.agrocontabil.assistente.ferramentas.ProcessadorErroFerramenta;
import br.com.agrocontabil.assistente.llm.ModeloLinguagem;
import br.com.agrocontabil.assistente.orquestracao.LlmClassificadorIntencao;
import br.com.agrocontabil.assistente.porta.DadosProdutorPorta;
import br.com.agrocontabil.assistente.porta.TratamentoProdutoPorta;
import br.com.agrocontabil.assistente.rag.BuscaLegislacaoService;
import br.com.agrocontabil.assistente.rag.TrechoRecuperado;
import br.com.agrocontabil.assistente.rag.ValidadorCitacao;
import br.com.agrocontabil.config.AssistenteProperties;
import br.com.agrocontabil.support.OllamaHttpSimulado;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * Cache do prefixo do prompt (#270), verificado <strong>no fio</strong>, com o
 * {@link OllamaChatModel} real: o Ollama só reaproveita o processamento do prompt (o prefill, o
 * gargalo na VPS sem GPU) até o primeiro byte diferente da requisição anterior. Então a parte fixa
 * de cada chamada — instrução de sistema e definições das funções — tem de ser igual byte a byte
 * entre perguntas diferentes, em dias diferentes, e vir antes de tudo que varia (data, pergunta,
 * trechos de lei).
 *
 * <p>E cada chamada leva só as funções que pode usar: a de dados, as de dados; a classificação e a
 * lei, nenhuma.
 */
class PrefixoPromptOllamaFioTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final LocalDate DIA_1 = LocalDate.of(2026, 9, 29);
  private static final LocalDate DIA_2 = LocalDate.of(2026, 11, 5);

  private OllamaHttpSimulado ollama;
  private List<String> corpos;
  private ModeloLinguagem modelo;

  @BeforeEach
  void setUp() throws IOException {
    ollama = OllamaHttpSimulado.iniciar();
    corpos = ollama.corpos();
    modelo = ollama.modelo();
  }

  @AfterEach
  void tearDown() {
    ollama.close();
  }

  @Test
  @DisplayName("Dados: sistema + funcoes iguais byte a byte entre perguntas e dias diferentes")
  void prefixoDaRotaDeDadosEstavel() throws IOException {
    ollama.responder(resposta(""));
    ollama.responder(resposta(""));

    // Duas instancias da rota: a ordem das funcoes nao pode depender da reflexao de cada uma.
    novaRotaDados().responder("Qual meu saldo de IBS em julho?",
        new ContextoAssistente(UUID.randomUUID(), DIA_1));
    novaRotaDados().responder("Quanto gastei com adubo este ano?",
        new ContextoAssistente(UUID.randomUUID(), DIA_2));

    JsonNode primeira = JSON.readTree(corpos.get(0));
    JsonNode segunda = JSON.readTree(corpos.get(1));
    assertThat(primeira.get("messages").get(0)).isEqualTo(segunda.get("messages").get(0));
    assertThat(primeira.get("tools")).isEqualTo(segunda.get("tools"));
    assertThat(primeira.get("messages").get(0).get("role").asText()).isEqualTo("system");
    assertThat(primeira.get("messages").get(0).get("content").asText())
        .isEqualTo(PromptsCeres.SISTEMA_DADOS)
        .doesNotContain("2026");

    // Os nomes em ordem: so as funcoes de dados, nenhuma outra.
    List<String> nomes = new ArrayList<>();
    primeira.get("tools").forEach(t -> nomes.add(t.get("function").get("name").asText()));
    assertThat(nomes).isSorted().doesNotHaveDuplicates()
        .contains("consultarApuracao", "resumoDoProdutor");

    // A parte variavel vem depois: data e pergunta na mensagem do usuario.
    assertThat(primeira.get("messages").get(1).get("content").asText())
        .isEqualTo("Hoje e 2026-09-29.\n\nPergunta: Qual meu saldo de IBS em julho?");
    assertThat(segunda.get("messages").get(1).get("content").asText())
        .startsWith("Hoje e 2026-11-05.");
  }

  @Test
  @DisplayName("Classificacao: sem funcoes e com o sistema constante")
  void classificacaoSemFuncoes() throws IOException {
    ollama.responder(resposta("{\\\"intencao\\\": \\\"DADOS\\\", \\\"confianca\\\": 0.9}"));
    ollama.responder(resposta("{\\\"intencao\\\": \\\"LEGISLACAO\\\", \\\"confianca\\\": 0.9}"));
    LlmClassificadorIntencao classificador = new LlmClassificadorIntencao(modelo, JSON, new AssistenteProperties());

    classificador.classificar("Quanto paguei de CBS?",
        new ContextoAssistente(UUID.randomUUID(), DIA_1));
    classificador.classificar("O que diz o art. 138?",
        new ContextoAssistente(UUID.randomUUID(), DIA_2));

    JsonNode primeira = JSON.readTree(corpos.get(0));
    JsonNode segunda = JSON.readTree(corpos.get(1));
    assertThat(semFuncoes(primeira)).isTrue();
    assertThat(primeira.get("messages").get(0)).isEqualTo(segunda.get("messages").get(0));
    assertThat(primeira.get("messages").get(0).get("content").asText())
        .isEqualTo(PromptsCeres.CLASSIFICACAO);
  }

  @Test
  @DisplayName("Lei: sem funcoes; regras antes da data e dos trechos, iguais entre perguntas")
  void prefixoDaRotaDeLegislacaoEstavel() throws IOException {
    BuscaLegislacaoService busca = mock(BuscaLegislacaoService.class);
    when(busca.buscar(anyString(), any())).thenReturn(
        List.of(trecho("T1", "id-1", "Os insumos agropecuarios tem reducao.")),
        List.of(trecho("T1", "id-2", "O produtor rural pode optar pelo regime regular.")));
    RotaLegislacao rota = new RotaLegislacao(modelo, busca, new ValidadorCitacao(),
        new ValidadorNumeros(), new VerificadorAfirmacaoDados(),
        new AssistenteProperties());
    ollama.responder(resposta("Os insumos tem reducao [T1]."));
    ollama.responder(resposta("O produtor pode optar [T1]."));

    rota.responder("Insumo tem reducao?", new ContextoAssistente(UUID.randomUUID(), DIA_1));
    rota.responder("Posso optar pelo regime regular?",
        new ContextoAssistente(UUID.randomUUID(), DIA_2));

    JsonNode primeira = JSON.readTree(corpos.get(0));
    JsonNode segunda = JSON.readTree(corpos.get(1));
    assertThat(semFuncoes(primeira)).isTrue();
    String sistema1 = primeira.get("messages").get(0).get("content").asText();
    String sistema2 = segunda.get("messages").get(0).get("content").asText();
    String fixo1 = sistema1.substring(0, sistema1.indexOf("Hoje e "));
    String fixo2 = sistema2.substring(0, sistema2.indexOf("Hoje e "));
    assertThat(fixo1).isEqualTo(fixo2).doesNotContain("2026", "insumos agropecuarios");
    // Tudo que varia fica depois da parte fixa.
    assertThat(sistema1.substring(fixo1.length()))
        .startsWith("Hoje e 2026-09-29.").contains("Os insumos agropecuarios tem reducao.");
  }

  @Test
  @DisplayName("Lei do MISTO: o prefixo das regras tambem e estavel e comeca como o da lei")
  void prefixoDaLeiDoMistoEstavel() throws IOException {
    BuscaLegislacaoService busca = mock(BuscaLegislacaoService.class);
    when(busca.buscar(anyString(), any())).thenReturn(
        List.of(trecho("T1", "id-1", "Os insumos agropecuarios tem reducao.")),
        List.of(trecho("T1", "id-2", "O produtor rural pode optar pelo regime regular.")),
        List.of(trecho("T1", "id-3", "Os insumos agropecuarios tem reducao.")));
    RotaLegislacao rota = new RotaLegislacao(modelo, busca, new ValidadorCitacao(),
        new ValidadorNumeros(), new VerificadorAfirmacaoDados(),
        new AssistenteProperties());
    ollama.responder(resposta("Os insumos tem reducao [T1]."));
    ollama.responder(resposta("O produtor pode optar [T1]."));
    ollama.responder(resposta("Os insumos tem reducao [T1]."));

    rota.responderRegraGeral("Insumo tem reducao?",
        new ContextoAssistente(UUID.randomUUID(), DIA_1), List.of(), true);
    rota.responderRegraGeral("Produtor pode optar pelo regime regular?",
        new ContextoAssistente(UUID.randomUUID(), DIA_2), List.of(), true);
    rota.responder("Insumo tem reducao?", new ContextoAssistente(UUID.randomUUID(), DIA_1));

    String misto1 = sistema(corpos.get(0));
    String misto2 = sistema(corpos.get(1));
    String lei = sistema(corpos.get(2));
    String fixoMisto = misto1.substring(0, misto1.indexOf("Hoje e "));
    assertThat(misto2).startsWith(fixoMisto);
    assertThat(fixoMisto).doesNotContain("2026", "insumos agropecuarios");
    // A regra extra do MISTO vem depois das regras comuns: as duas variantes dividem todas elas.
    String fixoLei = lei.substring(0, lei.indexOf("Hoje e "));
    assertThat(fixoMisto).startsWith(fixoLei.stripTrailing());
  }

  private static String sistema(String corpo) throws IOException {
    return JSON.readTree(corpo).get("messages").get(0).get("content").asText();
  }

  /** O Spring AI manda {@code "tools": []} quando não há função: vazio conta como nenhuma. */
  private static boolean semFuncoes(JsonNode corpo) {
    return !corpo.has("tools") || corpo.get("tools").isNull() || corpo.get("tools").isEmpty();
  }

  private RotaDados novaRotaDados() {
    return new RotaDados(modelo, new ValidadorNumeros(), new VerificadorNegativaDados(),
        new FerramentasDadosProdutor(mock(DadosProdutorPorta.class),
            mock(TratamentoProdutoPorta.class)),
        new ProcessadorErroFerramenta(), new AssistenteProperties(),
        new StaticListableBeanFactory().getBeanProvider(ObservationRegistry.class));
  }

  private static TrechoRecuperado trecho(String marca, String id, String texto) {
    return new TrechoRecuperado(marca, id, "LC 214/2025", "art. 138", "https://exemplo", texto,
        0.9);
  }

  private static String resposta(String conteudo) {
    return OllamaHttpSimulado.resposta(conteudo, null);
  }

}
