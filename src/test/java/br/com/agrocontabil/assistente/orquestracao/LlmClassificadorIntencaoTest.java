package br.com.agrocontabil.assistente.orquestracao;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.assistente.ContextoAssistente;
import br.com.agrocontabil.assistente.PromptsCeres;
import br.com.agrocontabil.assistente.llm.ModeloLinguagem;
import br.com.agrocontabil.support.ChatModelSimulado;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** Testes do {@link LlmClassificadorIntencao} (issue #157). */
class LlmClassificadorIntencaoTest {

  private ChatModelSimulado chatModel;
  private LlmClassificadorIntencao classificador;
  private final ContextoAssistente contexto =
      new ContextoAssistente(UUID.randomUUID(), LocalDate.of(2026, 9, 29));

  @BeforeEach
  void setUp() {
    chatModel = new ChatModelSimulado();
    StaticListableBeanFactory beans = new StaticListableBeanFactory();
    beans.addBean("chatModel", chatModel);
    classificador = new LlmClassificadorIntencao(
        new ModeloLinguagem(beans.getBeanProvider(ChatModel.class), "ollama",
            new br.com.agrocontabil.assistente.llm.DisponibilidadeModelo(
                java.time.Duration.ofSeconds(30), System::nanoTime)), new ObjectMapper());
  }

  @Test
  @DisplayName("Le o JSON da intencao; pergunta vai mascarada ao modelo")
  void leJson() {
    chatModel.responderTexto("{\"intencao\": \"legislacao\", \"confianca\": 0.87}");

    ClassificacaoIntencao c = classificador.classificar("CPF 123.456.789-09: qual a regra?", contexto);

    assertThat(c.intencao()).isEqualTo(Intencao.LEGISLACAO);
    assertThat(c.confianca()).isEqualTo(0.87);
    assertThat(chatModel.promptsRecebidos().get(0).getSystemMessage().getText())
        .isEqualTo(PromptsCeres.CLASSIFICACAO);
    assertThat(chatModel.promptsRecebidos().get(0).getUserMessage().getText())
        .contains("[CPF]").doesNotContain("123.456.789-09");
  }

  @Test
  @DisplayName("Raciocinio e texto em volta do JSON sao ignorados")
  void toleraTextoEmVolta() {
    ClassificacaoIntencao c = classificador.interpretar(
        "<think>hmm</think> Claro! {\"intencao\":\"MISTO\",\"confianca\":2} espero ter ajudado");

    assertThat(c.intencao()).isEqualTo(Intencao.MISTO);
    assertThat(c.confianca()).isEqualTo(1.0);
  }

  @Test
  @DisplayName("MISTO: le a pergunta de lei separada pelo classificador")
  void mistoLePerguntaLei() {
    ClassificacaoIntencao c = classificador.interpretar("{\"intencao\": \"MISTO\","
        + " \"confianca\": 0.8, \"perguntaLei\": \"  adubo da direito a credito?  \"}");

    assertThat(c.intencao()).isEqualTo(Intencao.MISTO);
    assertThat(c.perguntaLei()).isEqualTo("adubo da direito a credito?");
  }

  @Test
  @DisplayName("Pergunta de lei so no MISTO, so texto e nunca em branco")
  void perguntaLeiForaDoMistoIgnorada() {
    assertThat(classificador.interpretar("{\"intencao\": \"LEGISLACAO\", \"confianca\": 0.8,"
        + " \"perguntaLei\": \"outra pergunta\"}").perguntaLei()).isNull();
    assertThat(classificador.interpretar("{\"intencao\": \"MISTO\", \"confianca\": 0.8,"
        + " \"perguntaLei\": {\"x\": 1}}").perguntaLei()).isNull();
    assertThat(classificador.interpretar("{\"intencao\": \"MISTO\", \"confianca\": 0.8,"
        + " \"perguntaLei\": \"   \"}").perguntaLei()).isNull();
    assertThat(classificador.interpretar("{\"intencao\": \"MISTO\", \"confianca\": 0.8}")
        .perguntaLei()).isNull();
  }

  @Test
  @DisplayName("Resposta ilegivel ou intencao desconhecida: indeterminada (fora de escopo, 0)")
  void ilegivel() {
    for (String texto : new String[] {
        "DADOS", "{intencao: DADOS", "{\"intencao\":\"ROUBAR\",\"confianca\":1}", "", null}) {
      ClassificacaoIntencao c = classificador.interpretar(texto);
      assertThat(c.intencao()).as(String.valueOf(texto)).isEqualTo(Intencao.FORA_DE_ESCOPO);
      assertThat(c.confianca()).isZero();
    }
  }
}
