package br.com.agrocontabil.sefaz;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;

/**
 * Servidor SOAP falso da SEFAZ para os testes do transporte (issue #128): HTTPS com
 * <strong>certificado de cliente obrigatório</strong> (mTLS), confiando só no certificado de teste
 * do emitente. Responde o que o teste programar e guarda o que recebeu.
 */
final class ServidorSefazFalso implements AutoCloseable {

  /** Uma requisição recebida. */
  record Recebida(String caminho, String contentType, String corpo, String certificadoCliente) {}

  private final HttpsServer servidor;
  private final List<Recebida> recebidas = new CopyOnWriteArrayList<>();
  private volatile int status = 200;
  private volatile String corpo = "";
  private volatile long atrasoMs;
  private volatile long travaCorpoMs;

  ServidorSefazFalso() throws Exception {
    servidor = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    SSLContext ssl = contextoDoServidor();
    servidor.setHttpsConfigurator(new HttpsConfigurator(ssl) {
      @Override
      public void configure(HttpsParameters parametros) {
        SSLParameters ssl = getSSLContext().getDefaultSSLParameters();
        ssl.setNeedClientAuth(true);
        parametros.setSSLParameters(ssl);
      }
    });
    servidor.createContext("/", troca -> {
      String certificado = null;
      if (troca instanceof HttpsExchange https && https.getSSLSession() != null) {
        try {
          certificado = ((X509Certificate) https.getSSLSession().getPeerCertificates()[0])
              .getSubjectX500Principal().getName();
        } catch (Exception e) {
          certificado = null;
        }
      }
      String recebido;
      try (InputStream in = troca.getRequestBody()) {
        recebido = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      }
      recebidas.add(new Recebida(troca.getRequestURI().getPath(),
          troca.getRequestHeaders().getFirst("Content-Type"), recebido, certificado));
      if (atrasoMs > 0) {
        try {
          Thread.sleep(atrasoMs);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      byte[] resposta = corpo.getBytes(StandardCharsets.UTF_8);
      troca.getResponseHeaders().add("Content-Type", "application/soap+xml; charset=utf-8");
      try {
        troca.sendResponseHeaders(status, resposta.length == 0 ? -1 : resposta.length);
        if (resposta.length > 0) {
          try (OutputStream out = troca.getResponseBody()) {
            if (travaCorpoMs > 0) {
              // Cabecalhos e metade do corpo saem; o resto trava (SEFAZ que para no meio).
              out.write(resposta, 0, resposta.length / 2);
              out.flush();
              Thread.sleep(travaCorpoMs);
              out.write(resposta, resposta.length / 2, resposta.length - resposta.length / 2);
            } else {
              out.write(resposta);
            }
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        }
      } catch (IOException e) {
        // cliente desistiu (timeout de leitura do teste)
      } finally {
        troca.close();
      }
    });
    servidor.setExecutor(Executors.newCachedThreadPool());
    servidor.start();
  }

  /** Programa a próxima resposta. */
  ServidorSefazFalso responder(int status, String corpo) {
    this.status = status;
    this.corpo = corpo;
    return this;
  }

  /** Atraso antes de responder (para estourar o tempo de leitura do cliente). */
  ServidorSefazFalso atrasar(long ms) {
    this.atrasoMs = ms;
    return this;
  }

  /** Manda os cabeçalhos e metade do corpo, e trava antes do resto. */
  ServidorSefazFalso travarNoCorpo(long ms) {
    this.travaCorpoMs = ms;
    return this;
  }

  /** Endereço de um caminho neste servidor. */
  String url(String caminho) {
    return "https://localhost:" + servidor.getAddress().getPort() + caminho;
  }

  List<Recebida> recebidas() {
    return recebidas;
  }

  /** Envelope SOAP 1.2 de resposta com o retorno dentro de {@code nfeResultMsg}. */
  static String envelope(String servicoNamespace, String retorno) {
    return "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
        + "<soap:Envelope xmlns:soap=\"http://www.w3.org/2003/05/soap-envelope\"><soap:Body>"
        + "<nfeResultMsg xmlns=\"" + servicoNamespace + "\">" + retorno + "</nfeResultMsg>"
        + "</soap:Body></soap:Envelope>";
  }

  @Override
  public void close() {
    servidor.stop(0);
  }

  private static SSLContext contextoDoServidor() throws Exception {
    char[] senha = "changeit".toCharArray();
    KeyStore chave = KeyStore.getInstance("PKCS12");
    try (InputStream in = recurso("certs/sefaz-fake-servidor.p12")) {
      chave.load(in, senha);
    }
    KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    kmf.init(chave, senha);

    // O servidor so aceita o certificado de teste do emitente: prova que o cliente faz mTLS.
    KeyStore emitente = KeyStore.getInstance("PKCS12");
    try (InputStream in = recurso("certs/nfe-teste.p12")) {
      emitente.load(in, senha);
    }
    Certificate certEmitente = emitente.getCertificate("nfe-teste");
    KeyStore confiaveis = KeyStore.getInstance("PKCS12");
    confiaveis.load(null, null);
    confiaveis.setCertificateEntry("emitente", certEmitente);
    TrustManagerFactory tmf =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    tmf.init(confiaveis);

    SSLContext ssl = SSLContext.getInstance("TLS");
    ssl.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
    return ssl;
  }

  private static InputStream recurso(String caminho) {
    return ServidorSefazFalso.class.getClassLoader().getResourceAsStream(caminho);
  }
}
