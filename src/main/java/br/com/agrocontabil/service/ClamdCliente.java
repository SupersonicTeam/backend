package br.com.agrocontabil.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Cliente do {@code clamd} (ClamAV) pelo comando {@code INSTREAM} em TCP (issue #227).
 *
 * <p>Protocolo: {@code zINSTREAM\0}, o arquivo em blocos de 4 bytes de tamanho (big-endian) +
 * dados, um bloco de tamanho zero no fim; a resposta termina em {@code \0}:
 * {@code stream: OK}, {@code stream: <assinatura> FOUND} ou {@code ... ERROR}. Cada verificação
 * abre a própria conexão (o {@code clamd} fecha depois de responder ao {@code INSTREAM}).
 *
 * <p>Falha fechada: qualquer resposta que não seja {@code OK} ou {@code FOUND} vira
 * {@link AntivirusIndisponivelException} — o arquivo nunca é dado como limpo sem veredito.
 */
public class ClamdCliente implements VerificacaoAntivirus {

  /** Tamanho de cada bloco enviado (o limite do arquivo é o {@code StreamMaxLength} do clamd). */
  static final int BLOCO = 64 * 1024;

  /** Teto da resposta lida: o clamd responde uma linha curta. */
  private static final int RESPOSTA_MAXIMA = 1024;

  /** Teto do nome da assinatura guardado (coluna {@code assinatura_virus}). */
  private static final int ASSINATURA_MAXIMA = 200;

  private static final byte[] COMANDO = "zINSTREAM\0".getBytes(StandardCharsets.US_ASCII);
  private static final String PREFIXO = "stream:";
  private static final String ACHADO = " FOUND";

  private final String host;
  private final int porta;
  private final int connectTimeoutMs;
  private final int readTimeoutMs;

  /**
   * Cria o cliente.
   *
   * @param host           servidor do clamd
   * @param porta          porta TCP do clamd (3310)
   * @param connectTimeout espera pela conexão
   * @param readTimeout    espera pela resposta (inclui o tempo de verificação)
   */
  public ClamdCliente(String host, int porta, Duration connectTimeout, Duration readTimeout) {
    this.host = host;
    this.porta = porta;
    this.connectTimeoutMs = Math.toIntExact(connectTimeout.toMillis());
    this.readTimeoutMs = Math.toIntExact(readTimeout.toMillis());
  }

  @Override
  public ResultadoVerificacao verificar(byte[] conteudo) {
    String resposta;
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(host, porta), connectTimeoutMs);
      socket.setSoTimeout(readTimeoutMs);
      OutputStream saida = socket.getOutputStream();
      saida.write(COMANDO);
      for (int inicio = 0; inicio < conteudo.length; inicio += BLOCO) {
        int tamanho = Math.min(BLOCO, conteudo.length - inicio);
        saida.write(ByteBuffer.allocate(Integer.BYTES).putInt(tamanho).array());
        saida.write(conteudo, inicio, tamanho);
      }
      saida.write(new byte[Integer.BYTES]);
      saida.flush();
      resposta = ler(socket.getInputStream());
    } catch (IOException e) {
      throw new AntivirusIndisponivelException(
          "clamd inacessivel em " + host + ":" + porta + ": " + e.getMessage(), e, true);
    }
    return interpretar(resposta);
  }

  /**
   * Traduz a resposta do clamd.
   *
   * @param resposta linha recebida, sem o {@code \0}
   * @return limpo ou infectado
   * @throws AntivirusIndisponivelException resposta sem veredito
   */
  static ResultadoVerificacao interpretar(String resposta) {
    String linha = resposta.strip();
    if (!linha.startsWith(PREFIXO)) {
      throw recusa(linha);
    }
    String corpo = linha.substring(PREFIXO.length()).strip();
    if (corpo.equals("OK")) {
      return ResultadoVerificacao.limpo();
    }
    if (corpo.endsWith(ACHADO)) {
      String assinatura = corpo.substring(0, corpo.length() - ACHADO.length()).strip();
      if (assinatura.isEmpty()) {
        throw recusa(linha);
      }
      return ResultadoVerificacao.infectado(assinatura.length() > ASSINATURA_MAXIMA
          ? assinatura.substring(0, ASSINATURA_MAXIMA) : assinatura);
    }
    throw recusa(linha);
  }

  private static AntivirusIndisponivelException recusa(String linha) {
    String trecho = linha.length() > 120 ? linha.substring(0, 120) : linha;
    return new AntivirusIndisponivelException("clamd sem veredito: " + trecho, null, false);
  }

  private static String ler(InputStream entrada) throws IOException {
    ByteArrayOutputStream lido = new ByteArrayOutputStream();
    int b;
    while ((b = entrada.read()) != -1 && b != 0) {
      if (lido.size() >= RESPOSTA_MAXIMA) {
        break;
      }
      lido.write(b);
    }
    return lido.toString(StandardCharsets.US_ASCII);
  }
}
