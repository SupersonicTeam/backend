package br.com.agrocontabil.assistente.llm;

import br.com.agrocontabil.exception.AssistenteIndisponivelException;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.beans.BeansException;
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
 *       {@link ChatModel} está o Ollama, uma API compatível com a da OpenAI ou a da Anthropic
 *       ({@code spring.ai.model.chat}).</li>
 * </ul>
 *
 * <p>O {@link ChatModel} é resolvido de forma preguiçosa: com {@code spring.ai.model.chat=none} a
 * aplicação sobe e só a Ceres responde 503 — o restante do sistema não depende do modelo.
 *
 * <p>Timeout e retry vêm da configuração do Spring AI e do Boot ({@code spring.ai.retry.*},
 * {@code spring.http.client.*}); ver o {@code application.yml}. O HTTP 429 do provedor por API é
 * re-tentado e, esgotadas as tentativas, não abre o disjuntor ({@link #abreDisjuntor}).
 */
@Component
public class ModeloLinguagem {

  private static final Logger log = LoggerFactory.getLogger(ModeloLinguagem.class);

  /** Profundidade máxima percorrida na cadeia de causas (defesa contra ciclo). */
  private static final int PROFUNDIDADE_MAXIMA = 16;

  /** Motivo do fim quando a geração parou no teto: Ollama e API da OpenAI; API da Anthropic. */
  private static final Set<String> MOTIVOS_DE_CORTE = Set.of("length", "max_tokens");

  private final ObjectProvider<ChatModel> chatModel;
  private final String provedor;
  private final DisponibilidadeModelo disponibilidade;

  /**
   * Constrói o gateway.
   *
   * @param chatModel       provedor do {@link ChatModel} ativo (pode não existir)
   * @param provedor        valor de {@code spring.ai.model.chat} ({@code ollama}, {@code openai}...)
   * @param disponibilidade disjuntor do modo fallback (issue #146): toda chamada informa o desfecho
   */
  public ModeloLinguagem(
      ObjectProvider<ChatModel> chatModel,
      @Value("${spring.ai.model.chat:ollama}") String provedor,
      DisponibilidadeModelo disponibilidade) {
    this.chatModel = chatModel;
    this.provedor = provedor;
    this.disponibilidade = disponibilidade;
  }

  /**
   * Envia o prompt ao modelo e devolve a resposta.
   *
   * @param prompt prompt completo (mensagens + opções)
   * @return resposta do modelo
   * @throws AssistenteIndisponivelException se não houver modelo configurado ou a chamada falhar
   */
  public ChatResponse chamar(Prompt prompt) {
    ChatModel modelo;
    try {
      modelo = modeloAtivo();
    } catch (AssistenteIndisponivelException e) {
      disponibilidade.registrarFalha();
      throw e;
    }
    ChatResponse resposta;
    try {
      resposta = modelo.call(prompt);
    } catch (RuntimeException e) {
      if (abreDisjuntor(e)) {
        disponibilidade.registrarFalha();
      }
      // So o tipo da falha: a mensagem e o stack trace de um provedor por API podem ecoar a
      // requisicao, que carrega dados do produtor e, com a continuidade (#499), as trocas
      // anteriores inteiras. Nem em DEBUG.
      log.warn("Falha ao chamar o modelo de linguagem: provedor={} falha={}",
          provedor, e.getClass().getName());
      throw new AssistenteIndisponivelException(
          "Falha ao chamar o modelo " + provedor + ": " + e.getClass().getSimpleName(), e);
    }
    if (resposta == null || resposta.getResult() == null) {
      // Resposta vazia e um tropeco desta pergunta, nao queda do modelo: nao abre o disjuntor.
      throw new AssistenteIndisponivelException(
          "Modelo " + provedor + " devolveu resposta vazia", null);
    }
    disponibilidade.registrarSucesso();
    return resposta;
  }

  /**
   * Opções de uma chamada só com o teto de tokens gerados (#270). O resto (modelo, temperatura,
   * {@code num_ctx}, thinking) vem das opções padrão do provedor, mescladas pelo Spring AI. O
   * {@code maxTokens} vira {@code num_predict} no Ollama e {@code max_tokens} na API compatível com
   * a da OpenAI e na da Anthropic.
   *
   * @param maxTokens teto de tokens gerados desta chamada
   * @return opções da chamada
   */
  public static ChatOptions comTeto(int maxTokens) {
    return ChatOptions.builder().maxTokens(maxTokens).build();
  }

  /**
   * {@code true} se a geração parou pelo teto de tokens, não porque o modelo terminou (#270). O
   * texto cortado pode ainda parecer válido para os validadores: uma resposta de lei com a marca
   * certa e a frase pela metade, a primeira de duas chamadas de função. Por isso quem chama
   * descarta, em vez de conferir o texto.
   *
   * <p>Dois sinais, qualquer um basta: o motivo do fim ({@code done_reason: "length"} no Ollama,
   * {@code finish_reason: "length"} na API da OpenAI, que o Spring AI expõe como {@code LENGTH},
   * {@code stop_reason: "max_tokens"} na da Anthropic) e
   * os tokens gerados no teto ou acima. O segundo cobre o motivo ausente: o {@code OllamaChatModel}
   * só o preenche quando o Ollama manda as contagens, e um provedor compatível pode mandar um
   * motivo que o Spring AI não conhece.
   *
   * @param resposta resposta do modelo
   * @param teto     teto de tokens gerados mandado nesta chamada
   * @return se a resposta foi cortada pelo teto
   */
  public static boolean cortadaPeloTeto(ChatResponse resposta, int teto) {
    if (resposta == null || resposta.getResult() == null) {
      return false;
    }
    if (resposta.getResult().getMetadata() != null) {
      String motivo = resposta.getResult().getMetadata().getFinishReason();
      if (motivo != null
          && MOTIVOS_DE_CORTE.contains(motivo.strip().toLowerCase(Locale.ROOT))) {
        return true;
      }
    }
    Integer gerados = resposta.getMetadata() == null || resposta.getMetadata().getUsage() == null
        ? null : resposta.getMetadata().getUsage().getCompletionTokens();
    return gerados != null && gerados >= teto;
  }

  /**
   * Só indisponibilidade de verdade abre o disjuntor (issue #146), porque ele vale para todos os
   * produtores. Recusa do provedor a <em>esta</em> requisição ({@link NonTransientAiException}:
   * 4xx como filtro de conteúdo ou contexto grande demais) não abre: senão qualquer produtor, com
   * uma pergunta feita para ser recusada, deixaria a Ceres em fallback para todo mundo. Chave de
   * API inválida também é 4xx e também não abre — mas falha rápido, sem esperar timeout.
   *
   * <p>Limite de taxa do provedor por API (HTTP 429, {@link LimiteTaxaProvedorException}) que
   * esgotou as tentativas também não abre: o provedor está no ar e só recusou esta chamada. Abrir
   * deixaria todos os produtores em fallback pela pausa inteira por causa de um limite por minuto.
   * Erro 5xx e falha de conexão seguem abrindo.
   *
   * <p>Thread interrompida (tempo esgotado ou cliente que saiu) também não: a falha é da pergunta.
   */
  static boolean abreDisjuntor(RuntimeException falha) {
    return !(falha instanceof NonTransientAiException)
        && !limiteDeTaxa(falha)
        && !Thread.currentThread().isInterrupted();
  }

  /** Procura o limite de taxa na falha e nas causas dela (o chamador pode tê-la embrulhado). */
  private static boolean limiteDeTaxa(Throwable falha) {
    Throwable atual = falha;
    for (int i = 0; atual != null && i < PROFUNDIDADE_MAXIMA; i++) {
      if (atual instanceof LimiteTaxaProvedorException) {
        return true;
      }
      atual = atual.getCause() == atual ? null : atual.getCause();
    }
    return false;
  }

  /**
   * {@code true} com o modelo por API ({@code spring.ai.model.chat} {@code anthropic} ou
   * {@code openai}): o que vai ao modelo sai do servidor e do país (#499, aviso da continuidade).
   *
   * @return se o provedor é por API
   */
  public boolean porApi() {
    String nome = provedor == null ? "" : provedor.strip().toLowerCase(Locale.ROOT);
    return nome.equals("anthropic") || nome.equals("openai");
  }

  /**
   * Identificação do modelo em uso, para auditoria ({@code provedor/modelo}, ex.:
   * {@code ollama/qwen3:4b}). Nunca inclui chave nem URL.
   *
   * @return identificação do modelo; só o provedor quando o modelo não informa o nome
   */
  public String identificacao() {
    ChatModel modelo;
    try {
      modelo = chatModel.getIfAvailable();
    } catch (BeansException e) {
      return provedor;
    }
    if (modelo == null) {
      return provedor;
    }
    ChatOptions opcoes = modelo.getDefaultOptions();
    String nome = opcoes != null ? opcoes.getModel() : null;
    return nome == null || nome.isBlank() ? provedor : provedor + "/" + nome;
  }

  private ChatModel modeloAtivo() {
    ChatModel modelo;
    try {
      modelo = chatModel.getIfAvailable();
    } catch (BeansException e) {
      // Dois ChatModel no contexto (ex.: um bean proprio ao lado do autoconfigurado): erro de
      // configuracao, mas para o cliente continua sendo "Ceres indisponivel", nao 500 de NF-e.
      throw new AssistenteIndisponivelException(
          "ChatModel ambiguo ou invalido no contexto: " + e.getClass().getSimpleName(), e);
    }
    if (modelo == null) {
      throw new AssistenteIndisponivelException(
          "Nenhum ChatModel configurado (spring.ai.model.chat=" + provedor + ")", null);
    }
    return modelo;
  }
}
