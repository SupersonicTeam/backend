package br.com.agrocontabil.security;

import br.com.agrocontabil.config.CriptografiaProperties;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Assinatura das trocas com a Ceres (#499): o histórico da continuidade volta do cliente, e a
 * assinatura prende cada par (pergunta, resposta) ao produtor do token e à carga que o servidor
 * escreveu (rota, consulta, validade). Sem ela, o cliente fabricaria uma "resposta anterior" errada
 * para a Ceres "corrigir".
 *
 * <p>Formato {@code v1.<carga>.<mac>}, em Base64url sem preenchimento. MAC =
 * {@code HMAC-SHA256(k, "v1|" + produtorId + "|" + segmentoCarga + "|" + b64(sha256(pergunta)) +
 * "|" + b64(sha256(resposta)))}, com o segmento recebido exato (nunca o JSON reserializado) e
 * pergunta e resposta em UTF-8 estrito (surrogate solto não assina nem confere). A chave é
 * derivada da {@code CRIPTO_CHAVE} com o rótulo {@value #ROTULO}: trocar a chave mestra invalida
 * os pares vivos em silêncio.
 *
 * <p>Só formato e MAC: o significado da carga (validade, rota, consulta) é da Ceres, que a confere
 * depois do MAC. Nenhuma falha lança nem vai a log com o valor: a conferência é silenciosa.
 */
@Component
public class AssinaturaHistoricoConversa {

  /** Rótulo da derivação da chave. */
  static final String ROTULO = "agrocontabil:ceres:historico:v1";

  /** Versão do formato. */
  static final String VERSAO = "v1";

  /** Tamanho máximo da assinatura inteira, em caracteres ASCII. */
  public static final int TAMANHO_MAXIMO = 2048;

  /** Tamanho máximo da carga, em bytes. */
  public static final int CARGA_MAXIMA = 1500;

  private static final String HMAC = "HmacSHA256";
  private static final Pattern BASE64URL = Pattern.compile("[A-Za-z0-9_-]+");
  private static final Base64.Encoder CODIFICADOR = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DECODIFICADOR = Base64.getUrlDecoder();

  private final SecretKeySpec chave;

  /**
   * Deriva a chave da {@code CRIPTO_CHAVE}, com as mesmas recusas da cifra dos dados pessoais.
   *
   * @param propriedades configuração da criptografia
   */
  public AssinaturaHistoricoConversa(CriptografiaProperties propriedades) {
    byte[] derivada = CifraDeterministica.chaveDerivada(propriedades, ROTULO);
    try {
      this.chave = new SecretKeySpec(derivada, HMAC);
    } finally {
      Arrays.fill(derivada, (byte) 0);
    }
  }

  /**
   * Assina um par.
   *
   * @param produtorId produtor do token
   * @param carga      carga (JSON compacto), até {@value #CARGA_MAXIMA} bytes
   * @param pergunta   pergunta considerada
   * @param resposta   resposta entregue
   * @return a assinatura; vazio se o texto tem surrogate solto ou a carga passa do teto
   */
  public Optional<String> assinar(UUID produtorId, byte[] carga, String pergunta,
      String resposta) {
    if (carga == null || carga.length == 0 || carga.length > CARGA_MAXIMA) {
      return Optional.empty();
    }
    Optional<byte[]> perguntaUtf8 = utf8Estrito(pergunta);
    Optional<byte[]> respostaUtf8 = utf8Estrito(resposta);
    if (perguntaUtf8.isEmpty() || respostaUtf8.isEmpty()) {
      return Optional.empty();
    }
    String segmento = CODIFICADOR.encodeToString(carga);
    byte[] mac = mac(produtorId, segmento, perguntaUtf8.get(), respostaUtf8.get());
    String assinatura = VERSAO + "." + segmento + "." + CODIFICADOR.encodeToString(mac);
    return assinatura.length() > TAMANHO_MAXIMO ? Optional.empty() : Optional.of(assinatura);
  }

  /**
   * Confere um par, nesta ordem: tamanho, formato e alfabeto; codificação estrita do texto; MAC em
   * tempo constante; só então a carga em Base64url canônico.
   *
   * @param produtorId produtor do token (nunca o do cliente)
   * @param assinatura assinatura recebida
   * @param pergunta   pergunta recebida
   * @param resposta   resposta recebida
   * @return os bytes da carga, se tudo confere
   */
  public Optional<byte[]> conferir(UUID produtorId, String assinatura, String pergunta,
      String resposta) {
    if (produtorId == null || assinatura == null || assinatura.isEmpty()
        || assinatura.length() > TAMANHO_MAXIMO || !ascii(assinatura)) {
      return Optional.empty();
    }
    String[] partes = assinatura.split("\\.", -1);
    if (partes.length != 3 || !VERSAO.equals(partes[0])
        || !BASE64URL.matcher(partes[1]).matches() || !BASE64URL.matcher(partes[2]).matches()) {
      return Optional.empty();
    }
    Optional<byte[]> perguntaUtf8 = utf8Estrito(pergunta);
    Optional<byte[]> respostaUtf8 = utf8Estrito(resposta);
    if (perguntaUtf8.isEmpty() || respostaUtf8.isEmpty()) {
      return Optional.empty();
    }
    Optional<byte[]> recebido = decodificarCanonico(partes[2]);
    if (recebido.isEmpty()) {
      return Optional.empty();
    }
    byte[] esperado = mac(produtorId, partes[1], perguntaUtf8.get(), respostaUtf8.get());
    if (!MessageDigest.isEqual(esperado, recebido.get())) {
      return Optional.empty();
    }
    return decodificarCanonico(partes[1])
        .filter(carga -> carga.length > 0 && carga.length <= CARGA_MAXIMA);
  }

  private byte[] mac(UUID produtorId, String segmento, byte[] pergunta, byte[] resposta) {
    String entrada = VERSAO + "|" + produtorId + "|" + segmento + "|"
        + CODIFICADOR.encodeToString(sha256(pergunta)) + "|"
        + CODIFICADOR.encodeToString(sha256(resposta));
    try {
      Mac mac = Mac.getInstance(HMAC);
      mac.init(chave);
      return mac.doFinal(entrada.getBytes(StandardCharsets.US_ASCII));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 indisponivel", e);
    }
  }

  private static byte[] sha256(byte[] dados) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(dados);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("SHA-256 indisponivel", e);
    }
  }

  /** UTF-8 que recusa surrogate solto, em vez de trocá-lo por {@code ?} como o {@code getBytes}. */
  static Optional<byte[]> utf8Estrito(String texto) {
    if (texto == null) {
      return Optional.empty();
    }
    CharsetEncoder codificador = StandardCharsets.UTF_8.newEncoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT);
    try {
      ByteBuffer bytes = codificador.encode(CharBuffer.wrap(texto));
      byte[] saida = new byte[bytes.remaining()];
      bytes.get(saida);
      return Optional.of(saida);
    } catch (CharacterCodingException e) {
      return Optional.empty();
    }
  }

  /** Base64url sem preenchimento e canônico: o que volta a ser codificado igual. */
  private static Optional<byte[]> decodificarCanonico(String segmento) {
    try {
      byte[] bytes = DECODIFICADOR.decode(segmento);
      return CODIFICADOR.encodeToString(bytes).equals(segmento)
          ? Optional.of(bytes) : Optional.empty();
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  private static boolean ascii(String texto) {
    for (int i = 0; i < texto.length(); i++) {
      if (texto.charAt(i) > 0x7E || texto.charAt(i) < 0x21) {
        return false;
      }
    }
    return true;
  }
}
