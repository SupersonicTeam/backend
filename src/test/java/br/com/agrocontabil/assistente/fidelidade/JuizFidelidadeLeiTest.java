package br.com.agrocontabil.assistente.fidelidade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.agrocontabil.assistente.ContextoAssistente;
import br.com.agrocontabil.assistente.PerguntaInterrompidaException;
import br.com.agrocontabil.assistente.PromptsCeres;
import br.com.agrocontabil.assistente.llm.DisponibilidadeModelo;
import br.com.agrocontabil.assistente.llm.ModeloLinguagem;
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
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * O juiz da fidelidade da lei (#269, camada 2): uma chamada, um veredito por frase; só reprova,
 * e a falha fecha.
 */
class JuizFidelidadeLeiTest {

  private static final List<ConferenciaFidelidadeLei.FraseConferida> DUAS = List.of(
      new ConferenciaFidelidadeLei.FraseConferida("Os insumos têm redução de 60%.",
          List.of("Ficam reduzidas em 60% as alíquotas dos insumos")),
      new ConferenciaFidelidadeLei.FraseConferida("Vale para fertilizantes.",
          List.of("aplica-se aos fertilizantes", "e defensivos")));

  private ChatModelSimulado chatModel;
  private JuizFidelidadeLei juiz;
  private ContextoAssistente contexto;

  @BeforeEach
  void setUp() {
    chatModel = new ChatModelSimulado();
    StaticListableBeanFactory beans = new StaticListableBeanFactory();
    beans.addBean("chatModel", chatModel);
    ModeloLinguagem modelo = new ModeloLinguagem(beans.getBeanProvider(ChatModel.class), "ollama",
        new DisponibilidadeModelo(Duration.ofSeconds(30), System::nanoTime));
    juiz = new JuizFidelidadeLei(modelo, new AssistenteProperties());
    contexto = new ContextoAssistente(UUID.randomUUID(), LocalDate.of(2026, 11, 12));
  }

  @Test
  @DisplayName("todas SUSTENTA: aprova; instrução fixa, frases e passagens na mensagem")
  void todasSustentam() {
    chatModel.responderTexto("{\"vereditos\": [\"SUSTENTA\", \"sustenta\"]}");

    assertThat(juiz.julgar(DUAS, contexto)).isNull();

    Prompt prompt = chatModel.promptsRecebidos().get(0);
    assertThat(prompt.getSystemMessage().getText()).isEqualTo(PromptsCeres.JUIZ_FIDELIDADE);
    String mensagem = prompt.getUserMessage().getText();
    assertThat(mensagem).isEqualTo("""
        Frase 1: Os insumos têm redução de 60%.
        Passagem: Ficam reduzidas em 60% as alíquotas dos insumos

        Frase 2: Vale para fertilizantes.
        Passagem: aplica-se aos fertilizantes
        Passagem: e defensivos""");
    assertThat(contexto.juizLei()).isNotNull();
    assertThat(contexto.juizLei().tokensEntrada()).isPositive();
  }

  @Test
  @DisplayName("NAO_SUSTENTA ou CONTRADIZ reprovam; contradição pesa mais")
  void reprovacoes() {
    chatModel.responderTexto("{\"vereditos\": [\"SUSTENTA\", \"NAO_SUSTENTA\"]}");
    assertThat(juiz.julgar(DUAS, contexto))
        .isEqualTo(DesfechoFidelidadeLei.REPROVADA_JUIZ_NAO_SUSTENTA);

    chatModel.responderTexto("```json\n{\"vereditos\": [\"NAO_SUSTENTA\", \"CONTRADIZ\"]}\n```");
    assertThat(juiz.julgar(DUAS, contexto))
        .isEqualTo(DesfechoFidelidadeLei.REPROVADA_JUIZ_CONTRADIZ);

    // Grafia solta do modelo: acento, espaco ou hifen.
    chatModel.responderTexto("{\"vereditos\": [\"Sustenta\", \"NÃO SUSTENTA\"]}");
    assertThat(juiz.julgar(DUAS, contexto))
        .isEqualTo(DesfechoFidelidadeLei.REPROVADA_JUIZ_NAO_SUSTENTA);
    chatModel.responderTexto("{\"vereditos\": [\"sustenta\", \"não-sustenta\"]}");
    assertThat(juiz.julgar(DUAS, contexto))
        .isEqualTo(DesfechoFidelidadeLei.REPROVADA_JUIZ_NAO_SUSTENTA);
  }

  @Test
  @DisplayName("falha fecha: JSON ilegível, veredito desconhecido ou número de vereditos errado")
  void respostaForaDoFormatoFecha() {
    for (String resposta : List.of("SUSTENTA", "{\"vereditos\": [\"SUSTENTA\"]}",
        "{\"vereditos\": [\"SUSTENTA\", \"TALVEZ\"]}", "{\"vereditos\": [\"SUSTENTA\", 1]}",
        "{\"outra\": []}", "{vereditos: [", "")) {
      chatModel.responderTexto(resposta);
      assertThat(juiz.julgar(DUAS, contexto)).as(resposta)
          .isEqualTo(DesfechoFidelidadeLei.REPROVADA_JUIZ_FALHA);
    }
  }

  @Test
  @DisplayName("resposta cortada pelo teto fecha, mesmo com vereditos legíveis")
  void cortadaFecha() {
    chatModel.responderEmSequencia(new ChatResponse(List.of(new Generation(
        new AssistantMessage("{\"vereditos\": [\"SUSTENTA\", \"SUSTENTA\"]}"),
        ChatGenerationMetadata.builder().finishReason("length").build()))));

    assertThat(juiz.julgar(DUAS, contexto)).isEqualTo(DesfechoFidelidadeLei.REPROVADA_JUIZ_FALHA);
  }

  @Test
  @DisplayName("modelo indisponível fecha, e o tempo do juiz fica registrado sem tokens")
  void modeloIndisponivelFecha() {
    chatModel.falharCom(new IllegalStateException("fora do ar"));

    assertThat(juiz.julgar(DUAS, contexto)).isEqualTo(DesfechoFidelidadeLei.REPROVADA_JUIZ_FALHA);
    assertThat(contexto.juizLei()).isNotNull();
    assertThat(contexto.juizLei().tokensEntrada()).isZero();
    assertThat(contexto.juizLei().tokensSaida()).isZero();
  }

  @Test
  @DisplayName("pergunta interrompida continua sendo interrupção, não reprovação")
  void interrupcaoPropaga() {
    contexto.interromper(DesfechoInteracao.TEMPO_ESGOTADO);

    assertThatThrownBy(() -> juiz.julgar(DUAS, contexto))
        .isInstanceOf(PerguntaInterrompidaException.class);
    assertThat(chatModel.promptsRecebidos()).isEmpty();
    // O juiz nao rodou: as colunas dele ficam nulas na auditoria.
    assertThat(contexto.juizLei()).isNull();
  }

  @Test
  @DisplayName("o que vai ao modelo passa pelo DataSanitizer")
  void sanitiza() {
    chatModel.responderTexto("{\"vereditos\": [\"SUSTENTA\"]}");

    juiz.julgar(List.of(new ConferenciaFidelidadeLei.FraseConferida(
        "O produtor de CPF 123.456.789-09 tem redução.", List.of("redução"))), contexto);

    assertThat(chatModel.promptsRecebidos().get(0).getUserMessage().getText())
        .doesNotContain("123.456.789-09");
  }
  @Test
  @DisplayName("julgamento detalhado: o motivo da falha separa formato, contagem, corte e erro")
  void julgamentoDetalhado() {
    AssistenteProperties.FidelidadeLei.Juiz.Configuracao atual =
        AssistenteProperties.FidelidadeLei.Juiz.Configuracao.ATUAL;
    chatModel.responderTexto("{\"vereditos\": [\"SUSTENTA\", \"CONTRADIZ\"]}");
    JuizFidelidadeLei.Julgamento j = juiz.avaliar(DUAS, contexto, atual);
    assertThat(j.desfecho()).isEqualTo(DesfechoFidelidadeLei.REPROVADA_JUIZ_CONTRADIZ);
    assertThat(j.falha()).isNull();
    assertThat(j.vereditos()).containsExactly(JuizFidelidadeLei.Veredito.SUSTENTA,
        JuizFidelidadeLei.Veredito.CONTRADIZ);
    assertThat(j.cru()).contains("CONTRADIZ");

    chatModel.responderTexto("sem json");
    assertThat(juiz.avaliar(DUAS, contexto, atual).falha())
        .isEqualTo(JuizFidelidadeLei.Falha.FORMATO);
    chatModel.responderTexto("{\"vereditos\": [\"SUSTENTA\"]}");
    assertThat(juiz.avaliar(DUAS, contexto, atual).falha())
        .isEqualTo(JuizFidelidadeLei.Falha.CONTAGEM_DIFERENTE);
    chatModel.responderEmSequencia(new ChatResponse(List.of(new Generation(
        new AssistantMessage("{\"vereditos\": [\"SUSTENTA\", \"SUSTENTA\"]}"),
        ChatGenerationMetadata.builder().finishReason("length").build()))));
    JuizFidelidadeLei.Julgamento cortada = juiz.avaliar(DUAS, contexto, atual);
    assertThat(cortada.cortadaPeloTeto()).isTrue();
    assertThat(cortada.desfecho()).isEqualTo(DesfechoFidelidadeLei.REPROVADA_JUIZ_FALHA);
    chatModel.falharCom(new IllegalStateException("fora do ar"));
    assertThat(juiz.avaliar(DUAS, contexto, atual).falha())
        .isEqualTo(JuizFidelidadeLei.Falha.ERRO_MODELO);
  }

  @Test
  @DisplayName("configuração justificativa: outra instrução, teto próprio e o veredito lido do objeto")
  void modoJustificativa() {
    AssistenteProperties propriedades = new AssistenteProperties();
    propriedades.getFidelidadeLei().getJuiz()
        .setConfiguracao(AssistenteProperties.FidelidadeLei.Juiz.Configuracao.JUSTIFICATIVA);
    StaticListableBeanFactory beans = new StaticListableBeanFactory();
    beans.addBean("chatModel", chatModel);
    JuizFidelidadeLei comJustificativa = new JuizFidelidadeLei(
        new ModeloLinguagem(beans.getBeanProvider(ChatModel.class), "ollama",
            new DisponibilidadeModelo(Duration.ofSeconds(30), System::nanoTime)), propriedades);
    chatModel.responderTexto("{\"vereditos\": [{\"justificativa\": \"o caput reduz em 60%\", "
        + "\"veredito\": \"SUSTENTA\"}, {\"justificativa\": \"o § 1º cita fertilizantes\", "
        + "\"veredito\": \"Sustenta\"}]}");

    assertThat(comJustificativa.julgar(DUAS, contexto)).isNull();

    Prompt prompt = chatModel.promptsRecebidos().get(0);
    assertThat(prompt.getSystemMessage().getText())
        .isEqualTo(PromptsCeres.JUIZ_FIDELIDADE_COM_JUSTIFICATIVA)
        .contains("justificativa");
    assertThat(prompt.getOptions().getMaxTokens()).isEqualTo(1024);
    // Os criterios sao os mesmos nos dois modos; so o formato da resposta muda.
    assertThat(PromptsCeres.JUIZ_FIDELIDADE_COM_JUSTIFICATIVA).startsWith(PromptsCeres
        .JUIZ_FIDELIDADE.substring(0, PromptsCeres.JUIZ_FIDELIDADE.indexOf("Responda somente")));

    chatModel.responderTexto("{\"vereditos\": [{\"justificativa\": \"ok\"}, "
        + "{\"veredito\": \"SUSTENTA\"}]}");
    assertThat(comJustificativa.julgar(DUAS, contexto))
        .isEqualTo(DesfechoFidelidadeLei.REPROVADA_JUIZ_FALHA);
  }
}
