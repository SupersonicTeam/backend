package br.com.agrocontabil.assistente;

import br.com.agrocontabil.assistente.ferramentas.FerramentasDadosProdutor;
import br.com.agrocontabil.assistente.ferramentas.ProcessadorErroFerramenta;
import br.com.agrocontabil.assistente.llm.ModeloLinguagem;
import br.com.agrocontabil.config.AssistenteProperties;
import br.com.agrocontabil.model.DesfechoInteracao;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.resolution.StaticToolCallbackResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Rota de dados da Ceres (issue #142): o modelo escolhe funções de
 * {@link FerramentasDadosProdutor}, o backend as executa e o modelo redige a resposta com o
 * resultado.
 *
 * <p><strong>O laço é do backend</strong> ({@code internalToolExecutionEnabled=false} +
 * {@link ToolCallingManager}), não do Spring AI. Isso dá:
 * <ul>
 *   <li><strong>teto de rodadas</strong> ({@code app.assistente.max-rodadas-ferramentas}): sem
 *       ele, um modelo confuso ou induzido por prompt injection chamaria funções sem fim;</li>
 *   <li><strong>lista fechada de funções:</strong> o {@link ToolCallingManager} é próprio, com um
 *       {@link StaticToolCallbackResolver} só das funções de dados. O autoconfigurado também
 *       resolve qualquer bean {@code Function}/{@code Supplier} do contexto <em>pelo nome</em> — um
 *       nome injetado no prompt executaria código fora do isolamento. Pedido de função fora da
 *       lista encerra a pergunta sem executar nada;</li>
 *   <li><strong>os resultados das funções</strong>, que o {@link ValidadorNumeros} usa para
 *       conferir que todo número da resposta veio deles.</li>
 * </ul>
 *
 * <p>Não é transacional: cada função abre a própria transação curta de leitura. Nenhuma conexão do
 * pool fica presa enquanto o modelo gera (mesma lição das issues #96 e #99).
 */
@Component
public class RotaDados {

  /** O modelo pediu funções além do teto ou uma função que não existe. */
  static final String MENSAGEM_SEM_CONCLUSAO =
      "Nao consegui concluir a consulta. Tente reformular a pergunta de forma mais especifica.";

  /** O modelo não redigiu nada. */
  static final String MENSAGEM_SEM_RESPOSTA =
      "Nao consegui formular uma resposta. Tente reformular a pergunta.";

  /** A resposta tinha número que não veio de nenhuma função. */
  static final String MENSAGEM_NUMERO_SEM_FONTE =
      "Nao consegui confirmar os valores da resposta com os seus dados, por isso nao vou "
          + "mostra-los. Tente reformular a pergunta.";

  private static final Logger log = LoggerFactory.getLogger(RotaDados.class);

  /** Raciocínio que alguns modelos (qwen3) escrevem no texto quando o thinking não é separado. */
  private static final Pattern RACIOCINIO = Pattern.compile("(?s)<think>.*?</think>");

  private final ModeloLinguagem modelo;
  private final ToolCallingManager toolCallingManager;
  private final ValidadorNumeros validadorNumeros;
  private final List<ToolCallback> ferramentas;
  private final Set<String> nomesPermitidos;
  private final int maxRodadas;

  /** Constrói a rota com um {@link ToolCallingManager} restrito às funções de dados. */
  public RotaDados(
      ModeloLinguagem modelo,
      ValidadorNumeros validadorNumeros,
      FerramentasDadosProdutor ferramentasDadosProdutor,
      ProcessadorErroFerramenta processadorErro,
      AssistenteProperties propriedades,
      ObjectProvider<ObservationRegistry> observacao) {
    this.modelo = modelo;
    this.validadorNumeros = validadorNumeros;
    this.ferramentas = List.of(ToolCallbacks.from(ferramentasDadosProdutor));
    this.nomesPermitidos = ferramentas.stream()
        .map(f -> f.getToolDefinition().name())
        .collect(Collectors.toUnmodifiableSet());
    this.toolCallingManager = DefaultToolCallingManager.builder()
        .toolCallbackResolver(new StaticToolCallbackResolver(ferramentas))
        .toolExecutionExceptionProcessor(processadorErro)
        .observationRegistry(observacao.getIfAvailable(() -> ObservationRegistry.NOOP))
        .build();
    this.maxRodadas = propriedades.getMaxRodadasFerramentas();
  }

  /**
   * Responde a pergunta usando só as funções de dados.
   *
   * @param pergunta pergunta do usuário
   * @param contexto contexto da pergunta (produtor, data, fontes)
   * @return texto final e se ele foi aceito; as fontes usadas ficam no {@code contexto}
   * @throws br.com.agrocontabil.exception.AssistenteIndisponivelException se o modelo falhar
   */
  public ResultadoRota responder(String pergunta, ContextoAssistente contexto) {
    // A pergunta vai ao provedor do modelo: CPF, CNPJ, e-mail, telefone... saem mascarados (#144).
    String perguntaAoModelo = DataSanitizer.mascarar(pergunta);
    ToolCallingChatOptions opcoes = ToolCallingChatOptions.builder()
        .toolCallbacks(ferramentas)
        .internalToolExecutionEnabled(false)
        .toolContext(contexto.comoToolContext())
        .build();
    Prompt prompt = new Prompt(List.<Message>of(
        new SystemMessage(PromptsCeres.sistemaDados(contexto.hoje())),
        new UserMessage(perguntaAoModelo)), opcoes);

    List<String> resultados = new ArrayList<>();
    ChatResponse resposta = modelo.chamar(prompt);
    contexto.registrarUso(resposta);
    int rodadas = 0;
    while (resposta.hasToolCalls()) {
      if (rodadas >= maxRodadas) {
        log.warn("Ceres: teto de {} rodadas de funcoes atingido", maxRodadas);
        return ResultadoRota.descartada(MENSAGEM_SEM_CONCLUSAO, DesfechoInteracao.SEM_CONCLUSAO);
      }
      contexto.registrarChamadasDeFuncao(quantidadeDeChamadas(resposta));
      if (!somenteFuncoesPermitidas(resposta)) {
        // Nome alucinado ou injetado: nada e executado. O nome nao vai ao log (vem do modelo).
        log.warn("Ceres: modelo pediu funcao fora da lista de funcoes de dados");
        return ResultadoRota.descartada(
            MENSAGEM_SEM_CONCLUSAO, DesfechoInteracao.FUNCAO_NAO_PERMITIDA);
      }
      ToolExecutionResult execucao = toolCallingManager.executeToolCalls(prompt, resposta);
      coletarResultados(execucao.conversationHistory(), resultados);
      prompt = new Prompt(execucao.conversationHistory(), opcoes);
      resposta = modelo.chamar(prompt);
      contexto.registrarUso(resposta);
      rodadas++;
    }

    String texto = limpar(resposta.getResult().getOutput().getText());
    if (texto.isBlank()) {
      return ResultadoRota.descartada(MENSAGEM_SEM_RESPOSTA, DesfechoInteracao.SEM_RESPOSTA);
    }
    // Fontes de numeros: so os resultados das funcoes e a pergunta. O prompt de sistema NAO entra
    // (a data de hoje e as referencias legais liberariam 29, 2026, 214... como "com fonte"); dele
    // vale apenas a data de hoje, como data.
    List<String> fontes = new ArrayList<>(resultados);
    fontes.add(perguntaAoModelo);
    ValidadorNumeros.Resultado verificacao =
        validadorNumeros.verificar(texto, fontes, List.of(contexto.hoje()));
    if (!verificacao.rastreavel()) {
      // So a contagem: os numeros em si podem ser dados do produtor.
      log.warn("Ceres: resposta descartada com {} numero(s) sem fonte",
          verificacao.quantidadeSemFonte());
      return ResultadoRota.descartada(
          MENSAGEM_NUMERO_SEM_FONTE, DesfechoInteracao.NUMERO_SEM_FONTE);
    }
    return ResultadoRota.aceita(texto);
  }

  private static int quantidadeDeChamadas(ChatResponse resposta) {
    return resposta.getResults().stream().mapToInt(g -> g.getOutput().getToolCalls().size()).sum();
  }

  private boolean somenteFuncoesPermitidas(ChatResponse resposta) {
    return resposta.getResults().stream()
        .flatMap(g -> g.getOutput().getToolCalls().stream())
        .allMatch(chamada -> nomesPermitidos.contains(chamada.name()));
  }

  /** A última mensagem do histórico é a resposta das funções desta rodada. */
  private static void coletarResultados(List<Message> historico, List<String> resultados) {
    if (!historico.isEmpty()
        && historico.get(historico.size() - 1) instanceof ToolResponseMessage respostas) {
      respostas.getResponses().forEach(r -> resultados.add(r.responseData()));
    }
  }

  private static String limpar(String texto) {
    return texto == null ? "" : RACIOCINIO.matcher(texto).replaceAll("").strip();
  }
}
