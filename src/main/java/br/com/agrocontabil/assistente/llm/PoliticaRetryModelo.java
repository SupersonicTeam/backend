package br.com.agrocontabil.assistente.llm;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.util.Locale;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.retry.RetryContext;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.reactive.function.client.WebClientRequestException;

/**
 * Política de retry das chamadas ao modelo de linguagem (issue #141).
 *
 * <p>O {@code RetryTemplate} padrão do Spring AI re-tenta toda {@link ResourceAccessException} —
 * inclusive o <strong>read-timeout</strong>. Com o modelo local lento, isso dobra o tempo em que a
 * thread da requisição fica presa (2 × {@code AI_READ_TIMEOUT}) para repetir exatamente a chamada
 * que acabou de estourar. Mesmo raciocínio da SEFAZ (issue #83): só se repete o que não chegou ao
 * destino.
 *
 * <p>Re-tenta:
 * <ul>
 *   <li>{@link TransientAiException}: o provedor respondeu com erro transitório. É o 5xx e, por
 *       {@code spring.ai.retry.on-http-codes}, o 429 (limite de taxa), que chega como
 *       {@link LimiteTaxaProvedorException}. Sem essa propriedade o 429 seria um 4xx como os
 *       outros, porque {@code on-client-errors} é {@code false};</li>
 *   <li>falha de I/O em que a requisição comprovadamente <strong>não saiu</strong>: conexão
 *       recusada, host desconhecido, sem rota, connect-timeout.</li>
 * </ul>
 * Não re-tenta read-timeout, erro permanente do provedor (os demais 4xx: chave inválida,
 * requisição malformada) nem qualquer outra falha.
 *
 * <p>O 429 usa o mesmo backoff das outras falhas ({@code spring.ai.retry.backoff.*}), sem ler o
 * {@code Retry-After}: com o padrão de duas tentativas, é uma nova tentativa depois de um segundo.
 * Cobre o pico curto; limite por minuto já estourado segue para o modo fallback, sem abrir o
 * disjuntor ({@link ModeloLinguagem}).
 */
public class PoliticaRetryModelo extends SimpleRetryPolicy {

  /** Profundidade máxima percorrida na cadeia de causas (defesa contra ciclo). */
  private static final int PROFUNDIDADE_MAXIMA = 16;

  /**
   * Cria a política.
   *
   * @param maxTentativas total de tentativas, incluindo a primeira ({@code spring.ai.retry.max-attempts})
   */
  public PoliticaRetryModelo(int maxTentativas) {
    super(maxTentativas);
  }

  @Override
  public boolean canRetry(RetryContext context) {
    Throwable ultima = context.getLastThrowable();
    return (ultima == null || retentavel(ultima)) && super.canRetry(context);
  }

  /**
   * Decide se a falha vale uma nova tentativa.
   *
   * @param falha exceção da última tentativa
   * @return {@code true} para erro transitório do provedor ou requisição que não saiu
   */
  static boolean retentavel(Throwable falha) {
    if (falha instanceof TransientAiException) {
      return true;
    }
    if (falha instanceof ResourceAccessException || falha instanceof WebClientRequestException) {
      return requisicaoNaoSaiu(falha);
    }
    return false;
  }

  private static boolean requisicaoNaoSaiu(Throwable falha) {
    Throwable atual = falha;
    for (int i = 0; atual != null && i < PROFUNDIDADE_MAXIMA; i++) {
      if (atual instanceof ConnectException
          || atual instanceof UnknownHostException
          || atual instanceof NoRouteToHostException
          || atual instanceof HttpConnectTimeoutException
          || connectTimeoutDoSocket(atual)) {
        return true;
      }
      atual = atual.getCause() == atual ? null : atual.getCause();
    }
    return false;
  }

  /**
   * O {@code HttpURLConnection} usa {@link SocketTimeoutException} tanto para o connect-timeout
   * ("Connect timed out") quanto para o read-timeout ("Read timed out"); só a mensagem distingue.
   */
  private static boolean connectTimeoutDoSocket(Throwable falha) {
    return falha instanceof SocketTimeoutException
        && falha.getMessage() != null
        && falha.getMessage().toLowerCase(Locale.ROOT).contains("connect");
  }
}
