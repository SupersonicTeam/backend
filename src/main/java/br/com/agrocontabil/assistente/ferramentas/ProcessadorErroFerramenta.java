package br.com.agrocontabil.assistente.ferramentas;

import br.com.agrocontabil.assistente.ContextoAssistenteAusenteException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.stereotype.Component;

/**
 * Decide o que volta ao modelo quando uma função da Ceres falha (issue #142). Substitui o
 * processador padrão do Spring AI ({@code @ConditionalOnMissingBean}).
 *
 * <p>O padrão devolve ao modelo a mensagem da exceção — que pode ser uma mensagem do banco ou do
 * Hibernate, com nome de tabela e valores, e que com um provedor por API <em>sai do servidor</em>.
 * Aqui:
 * <ul>
 *   <li>{@link ParametroFerramentaInvalidoException}: a mensagem (escrita por nós, sem dado) volta
 *       ao modelo para ele corrigir a chamada;</li>
 *   <li>{@link ContextoAssistenteAusenteException}: defeito do backend — relançada, a pergunta é
 *       abortada em vez de seguir sem saber de quem são os dados;</li>
 *   <li>qualquer outra falha: mensagem genérica ao modelo; o tipo da falha vai ao log.</li>
 * </ul>
 */
@Component
public class ProcessadorErroFerramenta implements ToolExecutionExceptionProcessor {

  /** O que o modelo recebe quando a consulta falha por motivo técnico. */
  static final String FALHA_GENERICA =
      "A consulta falhou por um erro interno. Nao tente de novo; informe ao usuario que nao foi "
          + "possivel consultar esse dado agora.";

  private static final Logger log = LoggerFactory.getLogger(ProcessadorErroFerramenta.class);

  @Override
  public String process(ToolExecutionException falha) {
    Throwable causa = falha.getCause();
    String funcao = falha.getToolDefinition() == null ? "?" : falha.getToolDefinition().name();
    if (causa instanceof ContextoAssistenteAusenteException) {
      throw falha;
    }
    if (causa instanceof ParametroFerramentaInvalidoException parametro) {
      log.info("Funcao {} chamada com parametro invalido", funcao);
      return "Parametro invalido: " + parametro.getMessage();
    }
    log.warn("Falha na funcao {}: {}", funcao, causa == null ? "?" : causa.getClass().getName());
    return FALHA_GENERICA;
  }
}
