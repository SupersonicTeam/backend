package br.com.agrocontabil.assistente.explicacao;

import br.com.agrocontabil.assistente.ContextoAssistente;
import br.com.agrocontabil.assistente.DataSanitizer;
import br.com.agrocontabil.assistente.FonteDado;
import br.com.agrocontabil.assistente.GlossarioProdutor;
import br.com.agrocontabil.assistente.llm.ModeloLinguagem;
import br.com.agrocontabil.config.AssistenteProperties;
import br.com.agrocontabil.model.DesfechoExplicacao;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

/**
 * A chamada curta ao modelo que explica a resposta de dados (#268, com o que era da #415).
 *
 * <p>O modelo recebe <strong>só</strong> o texto do template já entregue ao produtor, um resumo das
 * consultas feitas (função, parâmetros e se achou algo) e as definições do {@link GlossarioProdutor}
 * dos termos que o texto usa; nunca os resultados crus das funções. Devolve 2 ou 3 frases, que o
 * {@link VerificadorFidelidade} confere antes de qualquer coisa sair. Tudo passa pelo
 * {@link DataSanitizer} (#144).
 *
 * <p>Teto de tokens próprio ({@code app.assistente.tetos.explicacao}) e corte conferido antes da
 * validação (#270): texto cortado é descartado, nunca conferido e entregue. A chamada passa pelo
 * {@link ContextoAssistente#chamar}, o ponto único que avisa o streaming e soma o uso; o prazo é de
 * quem chama ({@code AssistenteService}), que interrompe a thread.
 */
@Component
public class ExplicadorResposta {

  private static final Logger log = LoggerFactory.getLogger(ExplicadorResposta.class);

  private final ModeloLinguagem modelo;
  private final VerificadorFidelidade verificador;
  private final GlossarioProdutor glossario;
  private final int tetoSaida;

  /** Constrói o explicador com o teto de {@code app.assistente.tetos.explicacao}. */
  public ExplicadorResposta(ModeloLinguagem modelo, VerificadorFidelidade verificador,
      GlossarioProdutor glossario, AssistenteProperties propriedades) {
    this.modelo = modelo;
    this.verificador = verificador;
    this.glossario = glossario;
    this.tetoSaida = propriedades.getTetos().getExplicacao();
  }

  /**
   * Explicação conferida, ou o motivo do descarte.
   *
   * @param texto    texto aceito, num parágrafo; nulo se descartada
   * @param desfecho {@link DesfechoExplicacao#EXPLICACAO_ACEITA} ou o motivo
   */
  public record Explicacao(String texto, DesfechoExplicacao desfecho) {

    /** {@code true} se pode ir ao produtor. */
    public boolean aceita() {
      return desfecho == DesfechoExplicacao.EXPLICACAO_ACEITA;
    }
  }

  /**
   * Pede a explicação ao modelo e a confere.
   *
   * @param template texto da resposta de dados já entregue ao produtor
   * @param contexto contexto da pergunta (consultas, data, ponto único da chamada)
   * @return a explicação aceita ou o motivo do descarte
   * @throws br.com.agrocontabil.exception.AssistenteIndisponivelException se o modelo falhar
   * @throws br.com.agrocontabil.assistente.PerguntaInterrompidaException se a pergunta foi
   *     interrompida (cliente saiu)
   */
  public Explicacao explicar(String template, ContextoAssistente contexto) {
    Prompt prompt = prompt(template, contexto);
    ChatResponse resposta = contexto.chamar(modelo, prompt);
    if (ModeloLinguagem.cortadaPeloTeto(resposta, tetoSaida)) {
      log.warn("Ceres: explicacao cortada pelo teto de {} tokens", tetoSaida);
      return new Explicacao(null, DesfechoExplicacao.EXPLICACAO_DESCARTADA_CORTADA);
    }
    String texto = resposta.getResult().getOutput().getText();
    DesfechoExplicacao desfecho = verificador.verificar(texto, template);
    return desfecho == DesfechoExplicacao.EXPLICACAO_ACEITA
        ? new Explicacao(VerificadorFidelidade.paragrafo(texto), desfecho)
        : new Explicacao(null, desfecho);
  }

  /** O prompt: parte fixa no sistema; texto, consultas e termos na mensagem do usuário. */
  Prompt prompt(String template, ContextoAssistente contexto) {
    List<String> consultas = contexto.fontes().stream()
        .map(fonte -> consulta(fonte, contexto.fontesVazias().contains(fonte)))
        .toList();
    List<String> termos = glossario.termos().stream()
        .filter(t -> t.apareceEm(template))
        .map(ExplicadorResposta::termo)
        .toList();
    return new Prompt(List.<Message>of(
        new SystemMessage(PromptExplicacao.SISTEMA),
        new UserMessage(PromptExplicacao.mensagem(DataSanitizer.mascarar(template), consultas,
            termos))),
        ModeloLinguagem.comTeto(tetoSaida));
  }

  private static String consulta(FonteDado fonte, boolean vazia) {
    String parametros = fonte.parametros().entrySet().stream()
        .map(p -> p.getKey() + ": " + DataSanitizer.mascarar(p.getValue()))
        .collect(Collectors.joining("; "));
    return fonte.funcao() + (parametros.isEmpty() ? "" : " (" + parametros + ")")
        + (vazia ? ": nada encontrado" : ": com resultado");
  }

  private static String termo(GlossarioProdutor.Termo termo) {
    return termo.termo() + (termo.nome() == null ? "" : " (" + termo.nome() + ")") + ": "
        + termo.explicacao();
  }
}
