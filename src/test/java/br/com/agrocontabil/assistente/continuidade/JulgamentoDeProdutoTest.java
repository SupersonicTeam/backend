package br.com.agrocontabil.assistente.continuidade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.assistente.ContextoAssistente;
import br.com.agrocontabil.assistente.PromptsCeres;
import br.com.agrocontabil.assistente.continuidade.ConferenciaConsulta.DuvidaDeProduto;
import br.com.agrocontabil.assistente.llm.ModeloLinguagem;
import br.com.agrocontabil.config.AssistenteProperties;
import br.com.agrocontabil.exception.AssistenteIndisponivelException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/** O julgamento de produto da continuidade (#591): o que vai ao modelo e a falha que fecha. */
@ExtendWith(MockitoExtension.class)
class JulgamentoDeProdutoTest {

  private static final DuvidaDeProduto DUVIDA =
      new DuvidaDeProduto("diesel", List.of("herbicida", "setembro"));

  @Mock private ModeloLinguagem modelo;

  private final ContextoAssistente contexto =
      new ContextoAssistente(UUID.randomUUID(), LocalDate.of(2026, 11, 11));

  private JulgamentoDeProduto julgamento() {
    return new JulgamentoDeProduto(modelo, new AssistenteProperties());
  }

  @Test
  @DisplayName("OUTRO é a única prova; vão ao modelo só as palavras e o produto, nunca a pergunta")
  void outroEPromptEnxuto() {
    when(modelo.chamar(any())).thenReturn(resposta("OUTRO"));

    assertThat(julgamento().ehOutroProduto(DUVIDA, contexto)).isTrue();

    ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
    verify(modelo).chamar(prompt.capture());
    assertThat(prompt.getValue().getInstructions().get(0).getText())
        .isEqualTo(PromptsCeres.JULGAMENTO_PRODUTO);
    assertThat(prompt.getValue().getInstructions().get(1).getText())
        .isEqualTo("{\"palavras\":[\"herbicida\",\"setembro\"],\"produtoConsultado\":\"diesel\"}");
  }

  @Test
  @DisplayName("Pontuação e caixa não importam; qualquer outra coisa fecha sem prova")
  void formatoDaResposta() {
    when(modelo.chamar(any())).thenReturn(resposta("Outro."), resposta("MESMO"),
        resposta("INCERTO"), resposta("outro produto"), resposta("Não tenho certeza."),
        resposta(""));
    JulgamentoDeProduto julgamento = julgamento();

    assertThat(julgamento.ehOutroProduto(DUVIDA, contexto)).isTrue();
    assertThat(julgamento.ehOutroProduto(DUVIDA, contexto)).isFalse();
    assertThat(julgamento.ehOutroProduto(DUVIDA, contexto)).isFalse();
    assertThat(julgamento.ehOutroProduto(DUVIDA, contexto)).isFalse();
    assertThat(julgamento.ehOutroProduto(DUVIDA, contexto)).isFalse();
    assertThat(julgamento.ehOutroProduto(DUVIDA, contexto)).isFalse();
  }

  @Test
  @DisplayName("Modelo fora ou resposta cortada pelo teto: falha fecha, sem prova")
  void falhaFecha() {
    when(modelo.chamar(any())).thenThrow(new AssistenteIndisponivelException("fora", null))
        .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("OUTRO"),
            ChatGenerationMetadata.builder().finishReason("length").build()))));
    JulgamentoDeProduto julgamento = julgamento();

    assertThat(julgamento.ehOutroProduto(DUVIDA, contexto)).isFalse();
    assertThat(julgamento.ehOutroProduto(DUVIDA, contexto)).isFalse();
  }

  @Test
  @DisplayName("Sem palavra da pergunta não há o que julgar: nem chama o modelo")
  void semPalavras() {
    assertThat(julgamento().ehOutroProduto(new DuvidaDeProduto("diesel", List.of()), contexto))
        .isFalse();

    verify(modelo, never()).chamar(any());
  }

  private static ChatResponse resposta(String texto) {
    return new ChatResponse(List.of(new Generation(new AssistantMessage(texto))));
  }
}
