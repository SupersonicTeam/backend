package br.com.agrocontabil.assistente;

import br.com.agrocontabil.assistente.ferramentas.FerramentasDadosProdutor;
import br.com.agrocontabil.assistente.fallback.ModoFallback;
import br.com.agrocontabil.assistente.ferramentas.ProcessadorErroFerramenta;
import br.com.agrocontabil.assistente.llm.ModeloLinguagem;
import br.com.agrocontabil.config.AssistenteProperties;
import br.com.agrocontabil.model.DesfechoInteracao;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.resolution.StaticToolCallbackResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Rota de dados da Ceres (issues #142 e #266): o modelo <strong>só escolhe</strong> as funções de
 * {@link FerramentasDadosProdutor}; o backend as executa e a resposta sai dos templates
 * ({@code RedatorRespostaDados}), compostos pela {@link ComposicaoRespostaDados}. Os resultados não
 * voltam ao modelo: uma chamada só por pergunta, e o texto ao produtor nunca é do modelo (as
 * negativas falsas, os totais trocados e a instrução de função vazando nasciam na redação).
 *
 * <p><strong>O laço é do backend</strong> ({@code internalToolExecutionEnabled=false} +
 * {@link ToolCallingManager}), não do Spring AI. Isso dá:
 * <ul>
 *   <li><strong>uma rodada só</strong>: as funções pedidas na primeira resposta rodam uma vez, e
 *       nada mais é pedido ao modelo;</li>
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
      "Não consegui concluir a consulta. Tente reformular a pergunta de forma mais específica.";

  /**
   * O modelo não escolheu nenhuma função (ou todas falharam): resposta honesta, nunca negativa
   * (issue #266). Vai com os atalhos de tela.
   */
  static final String MENSAGEM_SEM_FUNCAO = "Não consegui identificar qual dado consultar. Você"
      + " pode perguntar, por exemplo: \"tenho guia em atraso?\", \"quanto vendi de soja em"
      + " outubro?\" ou \"tem algo me esperando?\". Ou veja direto na tela indicada.";

  /** A resposta tinha número que não veio de nenhuma função. */
  static final String MENSAGEM_NUMERO_SEM_FONTE =
      "Não consegui confirmar os valores da resposta com os seus dados, por isso não vou "
          + "mostrá-los. Tente reformular a pergunta.";

  /** Função da tabela oficial de classificação tributária (#283). */
  public static final String FUNCAO_TRATAMENTO = "consultarTratamentoProduto";

  /** Produtos consultados no máximo pela consulta depois da rota (#443). */
  static final int MAXIMO_PRODUTOS_TRATAMENTO = 3;

  private static final Logger log = LoggerFactory.getLogger(RotaDados.class);

  private final ModeloLinguagem modelo;
  private final ToolCallingManager toolCallingManager;
  private final ValidadorNumeros validadorNumeros;
  private final VerificadorNegativaDados verificadorNegativa;
  private final List<ToolCallback> ferramentas;
  private final int tetoSaida;
  private final Set<String> nomesPermitidos;

  /** Constrói a rota com um {@link ToolCallingManager} restrito às funções de dados. */
  public RotaDados(
      ModeloLinguagem modelo,
      ValidadorNumeros validadorNumeros,
      VerificadorNegativaDados verificadorNegativa,
      FerramentasDadosProdutor ferramentasDadosProdutor,
      ProcessadorErroFerramenta processadorErro,
      AssistenteProperties propriedades,
      ObjectProvider<ObservationRegistry> observacao) {
    this.modelo = modelo;
    this.tetoSaida = propriedades.getTetos().getFuncoes();
    this.validadorNumeros = validadorNumeros;
    this.verificadorNegativa = verificadorNegativa;
    // Ordem pelo nome (#270): o ToolCallbacks.from segue a ordem da reflexao, sem garantia da
    // JVM. As definicoes fazem parte do prefixo fixo do prompt; outra ordem invalidaria o cache.
    this.ferramentas = Stream.of(ToolCallbacks.from(ferramentasDadosProdutor))
        .sorted(Comparator.comparing(f -> f.getToolDefinition().name()))
        .toList();
    this.nomesPermitidos = ferramentas.stream()
        .map(f -> f.getToolDefinition().name())
        .collect(Collectors.toUnmodifiableSet());
    this.toolCallingManager = DefaultToolCallingManager.builder()
        .toolCallbackResolver(new StaticToolCallbackResolver(ferramentas))
        .toolExecutionExceptionProcessor(processadorErro)
        .observationRegistry(observacao.getIfAvailable(() -> ObservationRegistry.NOOP))
        .build();
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
        .maxTokens(tetoSaida)
        .build();
    Prompt prompt = new Prompt(List.<Message>of(
        new SystemMessage(PromptsCeres.SISTEMA_DADOS),
        new UserMessage(PromptsCeres.mensagemDados(contexto.hoje(), perguntaAoModelo))), opcoes);

    // Uma chamada so (#266): o modelo escolhe as funcoes; o texto e do template do backend.
    contexto.verificarAtiva();
    ChatResponse resposta = modelo.chamar(prompt);
    contexto.registrarUso(prompt, resposta);
    if (ModeloLinguagem.cortadaPeloTeto(resposta, tetoSaida)) {
      // Cortada no teto (#270): a ultima chamada pode ter se perdido e as outras sairiam como a
      // resposta inteira. Nada e executado.
      log.warn("Ceres: escolha de funcoes cortada pelo teto de {} tokens", tetoSaida);
      return new ResultadoRota(MENSAGEM_SEM_FUNCAO, DesfechoInteracao.RESPOSTA_CORTADA, List.of(),
          ModoFallback.atalhos(pergunta));
    }
    if (!resposta.hasToolCalls()) {
      // Sem funcao, nao ha dado para afirmar nada: nunca "nao tem" (#266, item 3).
      return new ResultadoRota(MENSAGEM_SEM_FUNCAO, DesfechoInteracao.SEM_RESPOSTA, List.of(),
          ModoFallback.atalhos(pergunta));
    }
    contexto.registrarChamadasDeFuncao(quantidadeDeChamadas(resposta));
    if (!somenteFuncoesPermitidas(resposta)) {
      // Nome alucinado ou injetado: nada e executado. O nome nao vai ao log (vem do modelo).
      log.warn("Ceres: modelo pediu funcao fora da lista de funcoes de dados");
      return ResultadoRota.descartada(
          MENSAGEM_SEM_CONCLUSAO, DesfechoInteracao.FUNCAO_NAO_PERMITIDA);
    }
    contexto.verificarAtiva();
    ToolExecutionResult execucao = toolCallingManager.executeToolCalls(prompt, resposta);
    List<String> resultados = new ArrayList<>();
    coletarResultados(execucao.conversationHistory(), resultados);
    contexto.verificarAtiva();
    return redigir(pergunta, perguntaAoModelo, resultados, contexto);
  }

  /**
   * Atalho fixo da tela (#343): a função é chamada direto pelo backend, sem modelo e sem tool
   * calling, e a resposta sai pelo mesmo template, a mesma composição e as mesmas conferências da
   * rota normal. Sem parâmetros: cada função tem o seu padrão (mês atual, próximos 30 dias). O
   * resultado passa pelo mesmo conversor das funções ({@code ResultadoSanitizadoConverter}), então
   * a fonte do {@link ValidadorNumeros} é a mesma do tool calling.
   *
   * @param funcao   nome da função de dados
   * @param pergunta pergunta do usuário (atalhos de tela na falha)
   * @param contexto contexto da pergunta
   * @return texto do template, ou a mensagem honesta se a consulta falhou
   * @throws IllegalArgumentException se a função não é de dados
   */
  public ResultadoRota responderAtalho(String funcao, String pergunta, ContextoAssistente contexto) {
    ToolCallback callback = ferramentas.stream()
        .filter(f -> f.getToolDefinition().name().equals(funcao))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Funcao de dados inexistente: " + funcao));
    contexto.verificarAtiva();
    // Uma chamada de funcao, como no tool calling: a auditoria conta o que rodou nos dados.
    contexto.registrarChamadasDeFuncao(1);
    String resultado;
    try {
      resultado = callback.call("{}", new ToolContext(contexto.comoToolContext()));
    } catch (ToolExecutionException e) {
      if (e.getCause() instanceof ContextoAssistenteAusenteException ausente) {
        throw ausente;
      }
      contexto.verificarAtiva();
      // Mesma regra do tool calling: consulta falhada nao vira negativa (#266).
      log.warn("Ceres: falha na funcao {} do atalho: {}", funcao,
          e.getCause() == null ? "?" : e.getCause().getClass().getName());
      return new ResultadoRota(MENSAGEM_SEM_FUNCAO, DesfechoInteracao.SEM_RESPOSTA, List.of(),
          ModoFallback.atalhos(pergunta));
    }
    contexto.verificarAtiva();
    return redigir(pergunta, DataSanitizer.mascarar(pergunta), List.of(resultado), contexto);
  }

  /**
   * Consulta a tabela oficial pelo produto que a pergunta nomeia, quando a rota não a consultou
   * (issue #443): uma chamada ao modelo com <strong>só</strong> a função da tabela, para ele dizer
   * qual é o produto; a consulta e o texto (com a ressalva do enquadramento) são do backend, e o
   * {@code TextosDoBackend} os põe na resposta da rota que for. Nada achado na tabela não muda a
   * resposta: cada chamada roda num contexto avulso, e só o que a tabela achou passa ao da
   * pergunta. Pedido de outra função não é executado.
   *
   * @param pergunta pergunta do usuário
   * @param contexto contexto da pergunta
   * @return {@code true} se algum produto foi achado na tabela
   * @throws br.com.agrocontabil.exception.AssistenteIndisponivelException se o modelo falhar
   */
  public boolean consultarTratamento(String pergunta, ContextoAssistente contexto) {
    ToolCallback tratamento = ferramentas.stream()
        .filter(f -> f.getToolDefinition().name().equals(FUNCAO_TRATAMENTO))
        .findFirst()
        .orElseThrow();
    ToolCallingChatOptions opcoes = ToolCallingChatOptions.builder()
        .toolCallbacks(List.of(tratamento))
        .internalToolExecutionEnabled(false)
        .toolContext(contexto.comoToolContext())
        .maxTokens(tetoSaida)
        .build();
    Prompt prompt = new Prompt(List.<Message>of(
        new SystemMessage(PromptsCeres.SISTEMA_TRATAMENTO),
        new UserMessage("Pergunta: " + DataSanitizer.mascarar(pergunta))), opcoes);
    contexto.verificarAtiva();
    ChatResponse resposta = modelo.chamar(prompt);
    contexto.registrarUso(prompt, resposta);
    if (ModeloLinguagem.cortadaPeloTeto(resposta, tetoSaida) || !resposta.hasToolCalls()) {
      return false;
    }
    List<AssistantMessage.ToolCall> chamadas = resposta.getResults().stream()
        .flatMap(g -> g.getOutput().getToolCalls().stream())
        .filter(c -> FUNCAO_TRATAMENTO.equals(c.name()))
        .limit(MAXIMO_PRODUTOS_TRATAMENTO)
        .toList();
    contexto.registrarChamadasDeFuncao(chamadas.size());
    boolean achou = false;
    for (AssistantMessage.ToolCall chamada : chamadas) {
      contexto.verificarAtiva();
      ContextoAssistente avulso = new ContextoAssistente(contexto.produtorId(), contexto.hoje());
      try {
        tratamento.call(chamada.arguments(), new ToolContext(avulso.comoToolContext()));
      } catch (ToolExecutionException e) {
        if (e.getCause() instanceof ContextoAssistenteAusenteException ausente) {
          throw ausente;
        }
        // Parametro recusado (nome curto demais, NCM incompleto): sem produto, nada muda.
        continue;
      }
      if (!avulso.fontesVazias().isEmpty()) {
        continue;
      }
      avulso.textosDoBackend()
          .forEach(t -> contexto.registrarTextoDoBackend(t.texto(), t.dispositivos()));
      avulso.fontes().forEach(f ->
          contexto.registrarConsultaDeTabela(f.funcao(), f.parametros(), false));
      achou = true;
    }
    return achou;
  }

  /**
   * Texto do template a partir das seções registradas pelas funções, com as redes de segurança
   * (#266): número sem fonte descarta, negativa sem recorte é trocada.
   */
  private ResultadoRota redigir(String pergunta, String perguntaAoModelo, List<String> resultados,
      ContextoAssistente contexto) {
    List<SecaoResposta> secoes = contexto.secoes();
    String texto = ComposicaoRespostaDados.compor(secoes);
    if (texto.isBlank() && !contexto.textosDoBackend().isEmpty()) {
      // So o tratamento do produto (#283): o texto e as citacoes vem do TextosDoBackend.
      return ResultadoRota.aceita("");
    }
    if (texto.isBlank()) {
      // Toda funcao pedida falhou (parametro invalido, tempo da funcao): sem dado, sem negativa.
      return new ResultadoRota(MENSAGEM_SEM_FUNCAO, DesfechoInteracao.SEM_RESPOSTA, List.of(),
          ModoFallback.atalhos(pergunta));
    }
    // Rede de seguranca (#266, item 6): o texto e do template, entao os numeros vem dos resultados
    // das funcoes; os pedacos de texto livre e de calculo do backend entram como fonte, e da
    // mensagem ao modelo so a pergunta mascarada. O prompt de sistema e a linha "Hoje e" da
    // mensagem do usuario (#270) NAO entram: liberariam 29, 2026, 214...
    List<String> fontes = new ArrayList<>(resultados);
    fontes.addAll(ComposicaoRespostaDados.textosLivres(secoes));
    fontes.add(perguntaAoModelo);
    ValidadorNumeros.Resultado verificacao =
        validadorNumeros.verificar(texto, fontes, List.of(contexto.hoje()));
    if (!verificacao.rastreavel()) {
      // So a contagem: os numeros em si podem ser dados do produtor.
      log.warn("Ceres: resposta de template descartada com {} numero(s) sem fonte",
          verificacao.quantidadeSemFonte());
      return ResultadoRota.descartada(
          MENSAGEM_NUMERO_SEM_FONTE, DesfechoInteracao.NUMERO_SEM_FONTE);
    }
    // Negativa sem recorte: o template sempre traz o recorte, mas a rede fica (#274).
    return verificadorNegativa.substituir(texto, contexto)
        .map(ResultadoRota::comNegativaSubstituida)
        .orElseGet(() -> ResultadoRota.aceita(texto));
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

}
