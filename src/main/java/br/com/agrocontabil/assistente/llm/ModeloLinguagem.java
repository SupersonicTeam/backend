package br.com.agrocontabil.assistente.llm;

import br.com.agrocontabil.exception.AssistenteIndisponivelException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Porta única do backend para o modelo de linguagem da Ceres (issue #141).
 *
 * <p>Toda chamada ao modelo passa por aqui, por dois motivos:
 * <ul>
 *   <li><strong>Falha vira exceção de domínio.</strong> Ollama fora do ar, timeout, chave de API
 *       recusada, erro do provedor: tudo sai como {@link AssistenteIndisponivelException} (503),
 *       com a mensagem genérica no envelope {@code errors[]}. O erro do provedor fica só no log —
 *       e sem a mensagem dele, que pode ecoar trechos da requisição.</li>
 *   <li><strong>O provedor é detalhe de configuração.</strong> Quem chama não sabe se atrás do
 *       {@link ChatModel} está o Ollama ou uma API compatível com a da OpenAI
 *       ({@code spring.ai.model.chat}).</li>
 * </ul>
 *
 * <p>O {@link ChatModel} é resolvido de forma preguiçosa: com {@code spring.ai.model.chat=none} a
 * aplicação sobe e só a Ceres responde 503 — o restante do sistema não depende do modelo.
 *
 * <p>Timeout e retry vêm da configuração do Spring AI e do Boot ({@code spring.ai.retry.*},
 * {@code spring.http.client.*}); ver o {@code application.yml}.
 */
@Component
public class ModeloLinguagem {

  private static final Logger log = LoggerFactory.getLogger(ModeloLinguagem.class);

  private final ObjectProvider<ChatModel> chatModel;
  private final String provedor;

  /**
   * Constrói o gateway.
   *
   * @param chatModel provedor do {@link ChatModel} ativo (pode não existir)
   * @param provedor  valor de {@code spring.ai.model.chat} ({@code ollama}, {@code openai}...)
   */
  public ModeloLinguagem(
      ObjectProvider<ChatModel> chatModel,
      @Value("${spring.ai.model.chat:ollama}") String provedor) {
    this.chatModel = chatModel;
    this.provedor = provedor;
  }

  /**
   * Envia o prompt ao modelo e devolve a resposta.
   *
   * @param prompt prompt completo (mensagens + opções)
   * @return resposta do modelo
   * @throws AssistenteIndisponivelException se não houver modelo configurado ou a chamada falhar
   */
  public ChatResponse chamar(Prompt prompt) {
    ChatModel modelo = modeloAtivo();
    try {
      ChatResponse resposta = modelo.call(prompt);
      if (resposta == null || resposta.getResult() == null) {
        throw new AssistenteIndisponivelException(
            "Modelo " + provedor + " devolveu resposta vazia", null);
      }
      return resposta;
    } catch (AssistenteIndisponivelException e) {
      throw e;
    } catch (RuntimeException e) {
      // So o tipo da falha: a mensagem de erro de um provedor por API pode ecoar a requisicao,
      // que carrega dados do produtor. A causa encadeada preserva o stack trace no log de 5xx.
      log.warn("Falha ao chamar o modelo de linguagem: provedor={} falha={}",
          provedor, e.getClass().getName());
      throw new AssistenteIndisponivelException(
          "Falha ao chamar o modelo " + provedor + ": " + e.getClass().getSimpleName(), e);
    }
  }

  /**
   * Identificação do modelo em uso, para auditoria ({@code provedor/modelo}, ex.:
   * {@code ollama/qwen3:4b}). Nunca inclui chave nem URL.
   *
   * @return identificação do modelo; só o provedor quando o modelo não informa o nome
   */
  public String identificacao() {
    ChatModel modelo = chatModel.getIfAvailable();
    if (modelo == null) {
      return provedor;
    }
    ChatOptions opcoes = modelo.getDefaultOptions();
    String nome = opcoes != null ? opcoes.getModel() : null;
    return nome == null || nome.isBlank() ? provedor : provedor + "/" + nome;
  }

  private ChatModel modeloAtivo() {
    ChatModel modelo = chatModel.getIfAvailable();
    if (modelo == null) {
      throw new AssistenteIndisponivelException(
          "Nenhum ChatModel configurado (spring.ai.model.chat=" + provedor + ")", null);
    }
    return modelo;
  }
}
