package br.com.agrocontabil.assistente.explicacao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.assistente.ContextoAssistente;
import br.com.agrocontabil.assistente.GlossarioProdutor;
import br.com.agrocontabil.assistente.ValidadorNumeros;
import br.com.agrocontabil.assistente.llm.ModeloLinguagem;
import br.com.agrocontabil.config.AssistenteProperties;
import br.com.agrocontabil.model.DesfechoExplicacao;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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

/**
 * A chamada da explicação (#268): o que vai ao modelo (só o template, o resumo das consultas e o
 * glossário dos termos presentes, mascarados), o teto próprio, o corte conferido antes da
 * validação e a conferência do {@link VerificadorFidelidade}.
 */
@ExtendWith(MockitoExtension.class)
class ExplicadorRespostaTest {

  private static final String TEMPLATE = "Entre 01/01/2026 e 30/06/2026 você comprou R$ 3.734,56"
      + " de fertilizante, em 2 notas de compra. A nota do fornecedor 123.456.789-09 está ok.";

  private static final String BOA = "Isso quer dizer que, no semestre, as suas compras de"
      + " fertilizante somaram R$ 3.734,56. Esse total junta as 2 notas de compra do período.";

  @Mock private ModeloLinguagem modelo;

  private ExplicadorResposta explicador;
  private ContextoAssistente contexto;
  private final AssistenteProperties propriedades = new AssistenteProperties();

  @BeforeEach
  void setUp() {
    explicador = new ExplicadorResposta(modelo, new VerificadorFidelidade(new ValidadorNumeros(),
        GlossarioProdutor.padrao(), propriedades), GlossarioProdutor.padrao(), propriedades);
    contexto = new ContextoAssistente(UUID.randomUUID(), LocalDate.of(2026, 11, 12));
    contexto.registrarFonte("totalComprasPorInsumo", Map.of("descricaoOuNcm", "fertilizante",
        "dataInicio", "2026-01-01", "dataFim", "2026-06-30"), false);
    contexto.registrarFonte("guiasEmAtraso", Map.of("ate", "2026-11-12"), true);
  }

  @Test
  @DisplayName("Aceita: o texto sai num parágrafo e a chamada usa o teto próprio")
  void aceita() {
    when(modelo.chamar(any())).thenReturn(texto(BOA + "\n"));

    ExplicadorResposta.Explicacao explicacao = explicador.explicar(TEMPLATE, contexto);

    assertThat(explicacao.aceita()).isTrue();
    assertThat(explicacao.texto()).isEqualTo(BOA);
    ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
    verify(modelo).chamar(prompt.capture());
    assertThat(prompt.getValue().getOptions().getMaxTokens())
        .isEqualTo(propriedades.getTetos().getExplicacao());
    // A chamada passou pelo ponto unico: o uso conta para a cota.
    assertThat(contexto.tokensSaida()).isPositive();
  }

  @Test
  @DisplayName("O modelo recebe só o template mascarado, as consultas e o glossário dos termos")
  void oQueVaiAoModelo() {
    Prompt prompt = explicador.prompt(TEMPLATE + " Saldo de IBS e CBS.", contexto);

    assertThat(prompt.getSystemMessage().getText()).isEqualTo(PromptExplicacao.SISTEMA);
    String mensagem = prompt.getUserMessage().getText();
    assertThat(mensagem)
        .startsWith("RESPOSTA DO SISTEMA:\nEntre 01/01/2026 e 30/06/2026 você comprou")
        // DataSanitizer (#144): o CPF do texto livre nao vai ao provedor.
        .doesNotContain("123.456.789-09").contains("[CPF]")
        .contains("CONSULTAS FEITAS:\n- totalComprasPorInsumo (")
        .contains("descricaoOuNcm: fertilizante")
        .contains(": com resultado")
        .contains("- guiasEmAtraso (ate: 2026-11-12): nada encontrado")
        .contains("TERMOS DO GLOSSARIO:\n- IBS (imposto sobre bens e serviços): ")
        .contains("- CBS (contribuição sobre bens e serviços): ")
        // So os termos presentes no texto.
        .doesNotContain("- ICMS");
  }

  @Test
  @DisplayName("Sem termo do glossário nem consulta: as listas dizem 'nenhum'")
  void listasVazias() {
    ContextoAssistente semConsulta = new ContextoAssistente(UUID.randomUUID(),
        LocalDate.of(2026, 11, 12));

    String mensagem = explicador.prompt("Você não tem pedidos abertos.", semConsulta)
        .getUserMessage().getText();

    assertThat(mensagem).contains("CONSULTAS FEITAS:\n- nenhum")
        .contains("TERMOS DO GLOSSARIO:\n- nenhum");
  }

  @Test
  @DisplayName("Cortada pelo teto (#270): descartada sem conferir o texto")
  void cortada() {
    when(modelo.chamar(any())).thenReturn(new ChatResponse(List.of(new Generation(
        new AssistantMessage(BOA),
        ChatGenerationMetadata.builder().finishReason("length").build()))));

    ExplicadorResposta.Explicacao explicacao = explicador.explicar(TEMPLATE, contexto);

    assertThat(explicacao.aceita()).isFalse();
    assertThat(explicacao.texto()).isNull();
    assertThat(explicacao.desfecho()).isEqualTo(DesfechoExplicacao.EXPLICACAO_DESCARTADA_CORTADA);
  }

  @Test
  @DisplayName("Reprovada pelo verificador: sem texto, com o motivo")
  void reprovada() {
    when(modelo.chamar(any())).thenReturn(texto("Você comprou R$ 4.000,00 de fertilizante no"
        + " semestre. Esse total junta as 2 notas de compra do período."));

    ExplicadorResposta.Explicacao explicacao = explicador.explicar(TEMPLATE, contexto);

    assertThat(explicacao.texto()).isNull();
    assertThat(explicacao.desfecho()).isEqualTo(DesfechoExplicacao.EXPLICACAO_DESCARTADA_NUMERO);
  }

  private static ChatResponse texto(String conteudo) {
    return new ChatResponse(List.of(new Generation(new AssistantMessage(conteudo))));
  }
}
