package br.com.agrocontabil.sefaz;

import br.com.agrocontabil.exception.SefazConexaoException;
import br.com.agrocontabil.exception.SefazRespostaIndeterminadaException;
import br.com.agrocontabil.nfe.SecureXmlFactories;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
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
    // O corpo inteiro e lido dentro do futuro: o prazo cobre a resposta toda, nao so os
    // cabecalhos (uma SEFAZ que travasse no meio do corpo prenderia a thread para sempre).
    CompletableFuture<HttpResponse<byte[]>> futuro =
        http.sendAsync(requisicao, info -> new CorpoLimitado(TAMANHO_MAXIMO_RESPOSTA));
    HttpResponse<byte[]> resposta;
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
      if (e.getCause() instanceof RespostaGrandeDemaisException) {
        throw new SefazRespostaIndeterminadaException(
            "Resposta da SEFAZ acima do tamanho maximo", e.getCause());
      }
      throw classificar(e.getCause());
    }
    return ler(resposta);
  }

  private static Document ler(HttpResponse<byte[]> resposta) {
    int status = resposta.statusCode();
    byte[] corpo = resposta.body();
    if (status >= 400 && status < 500) {
      throw new SefazConexaoException("SEFAZ recusou a requisicao: HTTP " + status);
    }
    if (status >= 500) {
      throw new SefazRespostaIndeterminadaException("SEFAZ respondeu HTTP " + status);
    }
    if (status != 200) {
      throw new SefazRespostaIndeterminadaException("Resposta inesperada da SEFAZ: HTTP " + status);
    }
    try {
      return SecureXmlFactories.hardenedDocumentBuilderFactory().newDocumentBuilder()
          .parse(new ByteArrayInputStream(corpo));
    } catch (Exception e) {
      throw new SefazRespostaIndeterminadaException("Resposta da SEFAZ ilegivel", e);
    }
  }

  /** O corpo passou do teto: a leitura para no meio, sem acumular o resto na memória. */
  static final class RespostaGrandeDemaisException extends IOException {
    RespostaGrandeDemaisException() {
      super("Resposta acima de " + TAMANHO_MAXIMO_RESPOSTA + " bytes");
    }
  }

  /** Acumula o corpo até o teto; acima dele cancela a leitura e falha. */
  static final class CorpoLimitado implements HttpResponse.BodySubscriber<byte[]> {

    private final int limite;
    private final ByteArrayOutputStream acumulado = new ByteArrayOutputStream();
    private final CompletableFuture<byte[]> resultado = new CompletableFuture<>();
    private Flow.Subscription assinatura;

    CorpoLimitado(int limite) {
      this.limite = limite;
    }

    @Override
    public CompletionStage<byte[]> getBody() {
      return resultado;
    }

    @Override
    public void onSubscribe(Flow.Subscription assinatura) {
      this.assinatura = assinatura;
      assinatura.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(List<ByteBuffer> partes) {
      if (resultado.isDone()) {
        return;
      }
      for (ByteBuffer parte : partes) {
        if (acumulado.size() + parte.remaining() > limite) {
          assinatura.cancel();
          resultado.completeExceptionally(new RespostaGrandeDemaisException());
          return;
        }
        byte[] bytes = new byte[parte.remaining()];
        parte.get(bytes);
        acumulado.writeBytes(bytes);
      }
    }

    @Override
    public void onError(Throwable erro) {
      resultado.completeExceptionally(erro);
    }

    @Override
    public void onComplete() {
      resultado.complete(acumulado.toByteArray());
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
