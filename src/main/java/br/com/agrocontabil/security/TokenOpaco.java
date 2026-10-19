package br.com.agrocontabil.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Token opaco de 256 bits e o hash com que ele é guardado — refresh (#207) e redefinição de senha
 * (#209). Um lugar só para a parte criptográfica: tamanho, fonte de aleatoriedade e hash não podem
 * divergir entre os dois usos.
 */
public final class TokenOpaco {

  /** 256 bits. */
  private static final int BYTES = 32;

  private static final SecureRandom ALEATORIO = new SecureRandom();

  private TokenOpaco() {
  }

  /**
   * Token novo, em base64url sem padding (cabe em cookie e em URL sem escape).
   *
   * @return token aleatório de 256 bits
   */
  public static String gerar() {
    byte[] bytes = new byte[BYTES];
    ALEATORIO.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /**
   * SHA-256 do token, em hexadecimal — o que vai para o banco.
   *
   * <p>Sem sal nem custo de propósito: o token já tem 256 bits de entropia (não há dicionário a
   * atacar, ao contrário de uma senha) e o hash precisa ser determinístico para a busca.
   *
   * @param token token
   * @return 64 caracteres hexadecimais
   */
  public static String hash(String token) {
    try {
      MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(sha256.digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 indisponivel na JVM", e);
    }
  }
}
