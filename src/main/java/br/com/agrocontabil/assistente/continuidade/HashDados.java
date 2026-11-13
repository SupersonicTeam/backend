package br.com.agrocontabil.assistente.continuidade;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Hash do resultado de dados (#499), para a autocorreção comparar a resposta nova com a anterior
 * pela carga assinada, nunca pelo texto que o cliente devolveu. Texto de dados aceito mais os
 * textos do backend, na ordem, juntados por {@code \n}, com espaços reduzidos e sem pontas; Base64url
 * dos 16 primeiros bytes do SHA-256.
 */
public final class HashDados {

  private static final Pattern ESPACOS = Pattern.compile("[ \\t\\x0B\\f\\r]+");

  private HashDados() {
  }

  /**
   * O texto normalizado que entra no hash.
   *
   * @param partes texto da parte de dados e os textos do backend, na ordem
   * @return o texto normalizado
   */
  public static String normalizar(List<String> partes) {
    String junto = String.join("\n", partes.stream().filter(Objects::nonNull).toList());
    return ESPACOS.matcher(junto).replaceAll(" ").strip();
  }

  /**
   * O hash de um texto já normalizado.
   *
   * @param normalizado texto de {@link #normalizar}
   * @return 22 caracteres Base64url
   */
  public static String de(String normalizado) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(normalizado.getBytes(StandardCharsets.UTF_8));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(digest, 16));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("SHA-256 indisponivel", e);
    }
  }
}
