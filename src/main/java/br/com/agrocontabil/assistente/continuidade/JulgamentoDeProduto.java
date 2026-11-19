package br.com.agrocontabil.assistente.continuidade;

import br.com.agrocontabil.assistente.ContextoAssistente;
import br.com.agrocontabil.assistente.DataSanitizer;
import br.com.agrocontabil.assistente.PromptsCeres;
import br.com.agrocontabil.assistente.continuidade.ConferenciaConsulta.DuvidaDeProduto;
import br.com.agrocontabil.assistente.llm.ModeloLinguagem;
import br.com.agrocontabil.config.AssistenteProperties;
import br.com.agrocontabil.exception.AssistenteIndisponivelException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

/**
 * Julgamento de produto do "tem certeza?" de dados (#591): quando o produto da consulta anterior
 * não aparece na pergunta e a tabela de produto não decide se é o mesmo, uma chamada curta ao
 * modelo diz se as palavras da pergunta nomeiam <strong>outro</strong> produto. Só a resposta
 * {@code OUTRO} vale como prova; {@code MESMO}, {@code INCERTO}, texto fora do formato, corte pelo
 * teto, falha do modelo ou reserva ausente fecham sem prova (a consulta é reexecutada).
 *
 * <p>O que vai ao provedor: até {@value #MAXIMO_PALAVRAS} palavras de conteúdo da pergunta, cada
 * uma pelo {@link DataSanitizer}, e o produto consultado; nunca a pergunta inteira, a resposta
 * anterior, o produtor ou valores. A chamada é feita pelo {@link ContextoAssistente#chamar} (ponto
 * único das chamadas ao modelo, com a reserva da cota) e fora de qualquer transação.
 */
@Component
public class JulgamentoDeProduto {

  /** Palavras da pergunta no prompt, no máximo. */
  static final int MAXIMO_PALAVRAS = 8;

  private static final int TAMANHO_MAXIMO = 60;
  private static final Logger log = LoggerFactory.getLogger(JulgamentoDeProduto.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  private final ModeloLinguagem modelo;
  private final int teto;

  /** Constrói o julgamento; o teto é {@code app.assistente.tetos.julgamento-produto}. */
  public JulgamentoDeProduto(ModeloLinguagem modelo, AssistenteProperties propriedades) {
    this.modelo = modelo;
    this.teto = propriedades.getTetos().getJulgamentoProduto();
  }

  /**
   * As palavras da pergunta nomeiam outro produto que o consultado.
   *
   * @param duvida   o produto da consulta e as palavras da pergunta
   * @param contexto contexto da pergunta
   * @return {@code true} só com a resposta {@code OUTRO}; falha fecha
   */
  public boolean ehOutroProduto(DuvidaDeProduto duvida, ContextoAssistente contexto) {
    List<String> palavras = duvida.termos().stream().limit(MAXIMO_PALAVRAS)
        .map(p -> limitar(DataSanitizer.mascarar(p))).toList();
    if (palavras.isEmpty()) {
      return false;
    }
    Map<String, Object> mensagem = new LinkedHashMap<>();
    mensagem.put("palavras", palavras);
    mensagem.put("produtoConsultado", limitar(DataSanitizer.mascarar(duvida.produto())));
    String json;
    try {
      json = JSON.writeValueAsString(mensagem);
    } catch (JsonProcessingException e) {
      return false;
    }
    Prompt prompt = new Prompt(List.<Message>of(new SystemMessage(PromptsCeres.JULGAMENTO_PRODUTO),
        new UserMessage(json)), ModeloLinguagem.comTeto(teto));
    ChatResponse resposta;
    try {
      resposta = contexto.chamar(modelo, prompt);
    } catch (AssistenteIndisponivelException e) {
      // Interrupcao (tempo esgotado, cliente que saiu) nao vira "sem prova": a pergunta acabou.
      contexto.verificarAtiva();
      return false;
    }
    if (ModeloLinguagem.cortadaPeloTeto(resposta, teto) || resposta.getResult() == null
        || resposta.getResult().getOutput() == null) {
      return false;
    }
    String lida = TextoContinuidade.normalizar(resposta.getResult().getOutput().getText())
        .replaceAll("[^a-z]", "");
    boolean outro = "outro".equals(lida);
    log.info("Ceres: {}", outro ? DesfechoContinuidade.JULGAMENTO_PRODUTO_OUTRO
        : DesfechoContinuidade.JULGAMENTO_PRODUTO_SEM_PROVA);
    return outro;
  }

  private static String limitar(String texto) {
    String limpo = texto == null ? "" : texto.replace('\n', ' ').replace('\r', ' ').strip();
    return limpo.length() > TAMANHO_MAXIMO ? limpo.substring(0, TAMANHO_MAXIMO) : limpo;
  }
}
