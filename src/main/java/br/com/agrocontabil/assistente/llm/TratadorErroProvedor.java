package br.com.agrocontabil.assistente.llm;

import java.io.IOException;
import java.net.URI;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResponseErrorHandler;

/**
 * Tratador das respostas de erro HTTP do provedor de chat, por cima do tratador do Spring AI.
 *
 * <p>Quem decide se o erro é transitório continua sendo o tratador do Spring AI, pela configuração
 * ({@code spring.ai.retry.on-http-codes}, {@code on-client-errors}). Este só dá tipo próprio ao
 * HTTP 429 que ele classificou como transitório ({@link LimiteTaxaProvedorException}). A exceção
 * do Spring AI leva o status apenas no texto da mensagem; aqui ele vem da resposta, sem casar
 * texto.
 *
 * <p>As duas variantes de {@code handleError} são repassadas, cada uma à mesma variante do
 * tratador de baixo. No Spring Framework 6.2 as duas são {@code default} e a de um argumento não
 * faz nada: repassar só ela engoliria o erro no dia em que o tratador do Spring AI implementasse
 * apenas a de três argumentos, que é a que o {@code RestClient} chama.
 */
public class TratadorErroProvedor implements ResponseErrorHandler {

  private final ResponseErrorHandler padrao;

  /**
   * Cria o tratador.
   *
   * @param padrao tratador do Spring AI, que classifica o erro pela configuração
   */
  public TratadorErroProvedor(ResponseErrorHandler padrao) {
    this.padrao = padrao;
  }

  @Override
  public boolean hasError(ClientHttpResponse resposta) throws IOException {
    return padrao.hasError(resposta);
  }

  @Override
  public void handleError(URI url, HttpMethod metodo, ClientHttpResponse resposta)
      throws IOException {
    comTipoProprio(resposta, () -> padrao.handleError(url, metodo, resposta));
  }

  @Override
  public void handleError(ClientHttpResponse resposta) throws IOException {
    comTipoProprio(resposta, () -> padrao.handleError(resposta));
  }

  private static void comTipoProprio(ClientHttpResponse resposta, Tratamento tratamento)
      throws IOException {
    boolean limiteDeTaxa =
        resposta.getStatusCode().value() == HttpStatus.TOO_MANY_REQUESTS.value();
    try {
      tratamento.tratar();
    } catch (TransientAiException e) {
      if (limiteDeTaxa) {
        throw new LimiteTaxaProvedorException(e.getMessage());
      }
      throw e;
    }
  }

  /** Chamada ao tratador de baixo, que pode falhar ao ler a resposta. */
  @FunctionalInterface
  private interface Tratamento {
    void tratar() throws IOException;
  }
}
