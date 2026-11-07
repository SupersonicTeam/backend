package br.com.agrocontabil.assistente;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.assistente.ferramentas.FerramentasDadosProdutor;
import br.com.agrocontabil.assistente.ferramentas.ProcessadorErroFerramenta;
import br.com.agrocontabil.assistente.llm.ModeloLinguagem;
import br.com.agrocontabil.assistente.porta.DadosProdutorPorta;
import br.com.agrocontabil.assistente.porta.TratamentoProdutoPorta;
import br.com.agrocontabil.assistente.porta.TratamentoProdutoPorta.Produto;
import br.com.agrocontabil.assistente.porta.TratamentoProdutoPorta.TratamentoConsultado;
import br.com.agrocontabil.config.AssistenteProperties;
import io.micrometer.observation.ObservationRegistry;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** Consulta à tabela oficial depois da rota (issue #443), com produtos inventados. */
class RotaDadosTratamentoTest {

  private static final LocalDate HOJE = LocalDate.of(2026, 11, 7);
  private static final Produto PIMENTA = new Produto("09041100", "Pimenta do gênero Piper", 1,
      "200034", new BigDecimal("60.00"), false, 7, "135", null, true);

  private final ModeloLinguagem modelo = mock(ModeloLinguagem.class);
  private final TratamentoProdutoPorta tabela = mock(TratamentoProdutoPorta.class);
  private RotaDados rota;
  private ContextoAssistente contexto;

  @BeforeEach
  void setUp() {
    rota = new RotaDados(modelo, new ValidadorNumeros(), new VerificadorNegativaDados(),
        new FerramentasDadosProdutor(mock(DadosProdutorPorta.class), tabela),
        new ProcessadorErroFerramenta(), new AssistenteProperties(),
        new StaticListableBeanFactory().getBeanProvider(ObservationRegistry.class));
    contexto = new ContextoAssistente(UUID.randomUUID(), HOJE);
  }

  private static ChatResponse pedir(String funcao, String argumentos) {
    return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
        .toolCalls(List.of(new AssistantMessage.ToolCall("t1", "function", funcao, argumentos)))
        .build())));
  }

  @Test
  @DisplayName("produto achado: texto da tabela com a ressalva e a fonte da tabela no contexto")
  void produtoAchado() {
    when(modelo.chamar(any())).thenReturn(
        pedir(RotaDados.FUNCAO_TRATAMENTO, "{\"produtoOuNcm\":\"pimenta\"}"));
    when(tabela.consultar("pimenta", HOJE)).thenReturn(
        new TratamentoConsultado("pimenta", List.of(PIMENTA), null));

    assertThat(rota.consultarTratamento("A pimenta tem redução?", contexto)).isTrue();

    assertThat(contexto.textosDoBackend()).singleElement()
        .satisfies(t -> assertThat(t.texto()).contains("redução de 60%").endsWith(
            "O enquadramento do seu produto nessa classificação se confirma com o seu contador."));
    assertThat(contexto.fontes()).singleElement()
        .satisfies(f -> assertThat(f.funcao()).isEqualTo(RotaDados.FUNCAO_TRATAMENTO));
    assertThat(contexto.fontesVazias()).isEmpty();
    // A chamada leva so a funcao da tabela e o prompt proprio.
    org.mockito.ArgumentCaptor<Prompt> prompt = org.mockito.ArgumentCaptor.forClass(Prompt.class);
    verify(modelo).chamar(prompt.capture());
    assertThat(prompt.getValue().getInstructions().get(0)).isInstanceOf(SystemMessage.class)
        .extracting(m -> ((SystemMessage) m).getText()).isEqualTo(PromptsCeres.SISTEMA_TRATAMENTO);
  }

  @Test
  @DisplayName("nada achado: o contexto da pergunta fica como estava")
  void nadaAchado() {
    when(modelo.chamar(any())).thenReturn(
        pedir(RotaDados.FUNCAO_TRATAMENTO, "{\"produtoOuNcm\":\"consultoria\"}"));
    when(tabela.consultar(eq("consultoria"), any()))
        .thenReturn(new TratamentoConsultado("consultoria", List.of(), null));

    assertThat(rota.consultarTratamento("Consultoria tem redução de IBS?", contexto)).isFalse();

    assertThat(contexto.textosDoBackend()).isEmpty();
    assertThat(contexto.fontes()).isEmpty();
  }

  @Test
  @DisplayName("sem chamada, outra função ou parâmetro recusado: nada executado, nada muda")
  void semProduto() {
    when(modelo.chamar(any())).thenReturn(
        new ChatResponse(List.of(new Generation(new AssistantMessage("")))),
        pedir("listarNotas", "{}"),
        pedir(RotaDados.FUNCAO_TRATAMENTO, "{\"produtoOuNcm\":\"x\"}"));

    assertThat(rota.consultarTratamento("Advogado tem redução de IBS?", contexto)).isFalse();
    assertThat(rota.consultarTratamento("Liste minhas notas com desconto de IBS", contexto))
        .isFalse();
    assertThat(rota.consultarTratamento("O x tem redução de CBS?", contexto)).isFalse();

    verify(tabela, never()).consultar(any(), any());
    assertThat(contexto.fontes()).isEmpty();
  }
}
