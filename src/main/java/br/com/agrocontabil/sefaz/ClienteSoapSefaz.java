package br.com.agrocontabil.sefaz;

import br.com.agrocontabil.exception.SefazConexaoException;
import br.com.agrocontabil.exception.SefazRespostaIndeterminadaException;
import br.com.agrocontabil.nfe.SecureXmlFactories;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import org.w3c.dom.Document;

/**
 * POST SOAP 1.2 com mTLS para a SEFAZ, com a classificação de falhas do contrato do
 * {@link SefazTransport} (issue #128).
 *
 * <ul>
 *   <li><strong>Antes de a requisição sair</strong> (DNS, conexão recusada ou estourada, handshake
 *       TLS) → {@link SefazConexaoException}: reenviar é seguro.</li>
 *   <li><strong>Depois</strong> (tempo de resposta estourado, conexão caída, HTTP 5xx, resposta
 *       ilegível ou grande demais) → {@link SefazRespostaIndeterminadaException}: o fisco pode ter
 *       processado.</li>
 *   <li>HTTP 4xx: o servidor recusou a requisição antes de processar (endereço errado, certificado
 *       recusado) → conexão.</li>
 *   <li>Qualquer outra falha → indeterminada: classificar como conexão uma falha pós-envio
 *       reabriria a duplicidade de NF-e.</li>
 * </ul>
 *
 * <p>O tempo total de uma chamada é limitado a {@code connect-ms + read-ms}: é o valor que a
 * validação de subida do {@code SefazProperties} usa para o pior caso de uma transmissão.
 */
final class ClienteSoapSefaz {

  /** Teto da resposta: um retorno da NF-e tem poucos KB; acima disso não é resposta válida. */
  static final int TAMANHO_MAXIMO_RESPOSTA = 1024 * 1024;

  private final HttpClient http;
  private final Duration timeoutLeitura;
  private final Duration timeoutTotal;

  /**
   * @param ssl            contexto TLS com o certificado do emitente (mTLS) e a cadeia da SEFAZ
   * @param timeoutConexao tempo máximo para conectar
   * @param timeoutLeitura tempo máximo para a resposta, depois de conectado
   */
  ClienteSoapSefaz(SSLContext ssl, Duration timeoutConexao, Duration timeoutLeitura) {
    SSLParameters parametros = ssl.getDefaultSSLParameters();
    parametros.setProtocols(new String[] {"TLSv1.3", "TLSv1.2"});
    this.http = HttpClient.newBuilder()
        .sslContext(ssl)
        .sslParameters(parametros)
        .connectTimeout(timeoutConexao)
        .followRedirects(HttpClient.Redirect.NEVER)
        .version(HttpClient.Version.HTTP_1_1)
        .build();
    this.timeoutLeitura = timeoutLeitura;
    this.timeoutTotal = timeoutConexao.plus(timeoutLeitura);
  }

  /**
   * Envia o envelope e devolve a resposta lida com o parser endurecido contra XXE.
   *
   * @param url      endereço do webservice
   * @param servico  serviço (para a ação SOAP)
   * @param envelope envelope SOAP 1.2
   * @return a resposta como DOM
   */
  Document enviar(String url, ServicoSefaz servico, String envelope) {
    HttpRequest requisicao = HttpRequest.newBuilder(URI.create(url))
        .timeout(timeoutLeitura)
        .header("Content-Type",
            "application/soap+xml; charset=utf-8; action=\"" + servico.acao() + "\"")
        .POST(HttpRequest.BodyPublishers.ofString(envelope, StandardCharsets.UTF_8))
        .build();
    CompletableFuture<HttpResponse<InputStream>> futuro =
        http.sendAsync(requisicao, HttpResponse.BodyHandlers.ofInputStream());
    HttpResponse<InputStream> resposta;
    try {
      resposta = futuro.get(timeoutTotal.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      futuro.cancel(true);
      throw new SefazRespostaIndeterminadaException(
          "SEFAZ nao respondeu em " + timeoutTotal.toMillis() + " ms", e);
    } catch (InterruptedException e) {
      futuro.cancel(true);
      Thread.currentThread().interrupt();
      throw new SefazRespostaIndeterminadaException("Envio a SEFAZ interrompido", e);
    } catch (ExecutionException e) {
      throw classificar(e.getCause());
    }
    return ler(resposta);
  }

  private static Document ler(HttpResponse<InputStream> resposta) {
    int status = resposta.statusCode();
    byte[] corpo;
    try (InputStream in = resposta.body()) {
      corpo = in.readNBytes(TAMANHO_MAXIMO_RESPOSTA + 1);
    } catch (IOException e) {
      throw new SefazRespostaIndeterminadaException("Falha ao ler a resposta da SEFAZ", e);
    }
    if (status >= 400 && status < 500) {
      throw new SefazConexaoException("SEFAZ recusou a requisicao: HTTP " + status);
    }
    if (status >= 500) {
      throw new SefazRespostaIndeterminadaException("SEFAZ respondeu HTTP " + status);
    }
    if (status != 200) {
      throw new SefazRespostaIndeterminadaException("Resposta inesperada da SEFAZ: HTTP " + status);
    }
    if (corpo.length > TAMANHO_MAXIMO_RESPOSTA) {
      throw new SefazRespostaIndeterminadaException("Resposta da SEFAZ acima do tamanho maximo");
    }
    try {
      return SecureXmlFactories.hardenedDocumentBuilderFactory().newDocumentBuilder()
          .parse(new ByteArrayInputStream(corpo));
    } catch (Exception e) {
      throw new SefazRespostaIndeterminadaException("Resposta da SEFAZ ilegivel", e);
    }
  }

  /** Falha de I/O do cliente HTTP: só o que comprovadamente aconteceu antes do envio é conexão. */
  static RuntimeException classificar(Throwable causa) {
    Throwable t = causa;
    while (t != null) {
      if (t instanceof HttpConnectTimeoutException || t instanceof ConnectException
          || t instanceof UnknownHostException || t instanceof UnresolvedAddressException
          || t instanceof SSLHandshakeException) {
        return new SefazConexaoException(
            "Requisicao nao chegou a SEFAZ: " + t.getClass().getSimpleName(), causa);
      }
      t = t.getCause();
    }
    return new SefazRespostaIndeterminadaException(
        "Falha na comunicacao com a SEFAZ depois do envio: "
            + (causa == null ? "desconhecida" : causa.getClass().getSimpleName()), causa);
  }
}
