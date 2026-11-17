package br.com.agrocontabil.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Derivação de chaves a partir da chave mestra ({@code CRIPTO_CHAVE}): HMAC-SHA256 da mestra
 * sobre um rótulo, um rótulo por uso. Extraída da {@link CifraDeterministica} (#499) com os
 * mesmos bytes: a cifra dos dados pessoais segue lendo o que já gravou.
 */
public final class DerivacaoChave {

  private static final String HMAC = "HmacSHA256";

  private DerivacaoChave() {
  }

  /**
   * Deriva uma chave.
   *
   * @param mestra chave mestra
   * @param rotulo rótulo do uso (ex.: {@code agrocontabil:cripto:cifra:v1})
   * @return 32 bytes
   */
  public static byte[] derivar(byte[] mestra, String rotulo) {
    try {
      Mac mac = Mac.getInstance(HMAC);
      mac.init(new SecretKeySpec(mestra, HMAC));
      return mac.doFinal(rotulo.getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 indisponivel", e);
    }
  }
}
