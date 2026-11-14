package br.com.agrocontabil.assistente;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.assistente.llm.ModeloLinguagem;
import br.com.agrocontabil.exception.AssistenteIndisponivelException;
import br.com.agrocontabil.model.DesfechoInteracao;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * Ponto único das chamadas ao modelo (#499): {@link ContextoAssistente#chamar} confere a reserva,
 * avisa quem transmite antes de gastar, guarda a estimativa da entrada da chamada em curso e
 * registra o uso.
 */
@ExtendWith(MockitoExtension.class)
class ContextoAssistenteChamadaTest {

  /** 400 caracteres: 100 tokens estimados. */
  private static final Prompt PROMPT = new Prompt(new UserMessage("x".repeat(400)));

  @Mock private ModeloLinguagem modelo;

  private final ContextoAssistente contexto =
      new ContextoAssistente(UUID.randomUUID(), LocalDate.of(2026, 11, 10));

  @Test
  @DisplayName("Avisa antes de chamar, chama uma vez e registra o uso informado")
  void avisaChamaERegistra() {
    List<String> ordem = new ArrayList<>();
    contexto.aoChamarOModelo(() -> ordem.add("aviso"));
    when(modelo.chamar(PROMPT)).thenAnswer(inv -> {
      ordem.add("modelo");
      return respostaComUso(1200, 80);
    });

    ChatResponse resposta = contexto.chamar(modelo, PROMPT);

    assertThat(resposta.getResult().getOutput().getText()).isEqualTo("ok");
    assertThat(ordem).containsExactly("aviso", "modelo");
    assertThat(contexto.tokensEntrada()).isEqualTo(1200);
    assertThat(contexto.tokensSaida()).isEqualTo(80);
    // A estimativa da chamada em curso zera quando ela termina: o gasto e so o informado.
    assertThat(contexto.gasto()).isEqualTo(new ContextoAssistente.Gasto(1200, 80));
  }

  @Test
  @DisplayName("Sem reserva para o modelo (atalho, conversa leve), a chamada e recusada")
  void semReservaRecusa() {
    List<String> avisos = new ArrayList<>();
    contexto.aoChamarOModelo(() -> avisos.add("aviso"));
    contexto.semReservaParaOModelo();

    assertThatThrownBy(() -> contexto.chamar(modelo, PROMPT))
        .isInstanceOf(IllegalStateException.class);

    verifyNoInteractions(modelo);
    assertThat(avisos).isEmpty();
    assertThat(contexto.gasto().nada()).isTrue();
  }

  @Test
  @DisplayName("Pergunta interrompida nao avisa nem chama")
  void interrompidaNaoChama() {
    List<String> avisos = new ArrayList<>();
    contexto.aoChamarOModelo(() -> avisos.add("aviso"));
    contexto.interromper(DesfechoInteracao.CANCELADA);

    assertThatThrownBy(() -> contexto.chamar(modelo, PROMPT))
        .isInstanceOf(PerguntaInterrompidaException.class);

    verifyNoInteractions(modelo);
    assertThat(avisos).isEmpty();
  }

  @Test
  @DisplayName("O aviso que percebe o cliente que saiu para antes de gastar")
  void avisoInterrompe() {
    contexto.aoChamarOModelo(() -> {
      contexto.interromper(DesfechoInteracao.CANCELADA);
      throw new PerguntaInterrompidaException();
    });

    assertThatThrownBy(() -> contexto.chamar(modelo, PROMPT))
        .isInstanceOf(PerguntaInterrompidaException.class);

    verifyNoInteractions(modelo);
    assertThat(contexto.gasto().nada()).isTrue();
  }

  @Test
  @DisplayName("Chamada que falha no meio deixa a entrada estimada no gasto, sem saida")
  void falhaNoMeioGuardaEstimativa() {
    when(modelo.chamar(any())).thenReturn(respostaComUso(1200, 80))
        .thenThrow(new AssistenteIndisponivelException("interrompida", null));
    contexto.chamar(modelo, PROMPT);

    assertThatThrownBy(() -> contexto.chamar(modelo, PROMPT))
        .isInstanceOf(AssistenteIndisponivelException.class);

    // A concluida pelo informado; a interrompida pela entrada estimada (400 / 4).
    assertThat(contexto.gasto()).isEqualTo(new ContextoAssistente.Gasto(1300, 80));
    // O acerto normal segue sem a estimativa: so o cancelamento pelo cliente a cobra.
    assertThat(contexto.tokensEntrada()).isEqualTo(1200);
  }

  @Test
  @DisplayName("Sem nenhuma chamada, o gasto e nada")
  void semChamadaNada() {
    assertThat(contexto.gasto().nada()).isTrue();
    assertThat(contexto.tokensEntrada()).isNull();
  }

  static ChatResponse respostaComUso(int entrada, int saida) {
    return new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))),
        ChatResponseMetadata.builder().usage(new DefaultUsage(entrada, saida)).build());
  }
}
