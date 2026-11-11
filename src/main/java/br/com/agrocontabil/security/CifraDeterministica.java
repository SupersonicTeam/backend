package br.com.agrocontabil.security;

import br.com.agrocontabil.config.CriptografiaProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cifra AES-256-GCM <strong>determinística</strong> dos dados pessoais em repouso (issue #147,
 * RNF004).
 *
 * <p><strong>Por que determinística.</strong> CPF/CNPJ e e-mail são consultados por igualdade
 * ({@code findByCpfCnpj}, {@code existsByEmail}, o {@code IN} das grafias do cadastro) e são
 * únicos no banco. Com IV aleatório cada gravação sairia diferente e as duas coisas quebrariam;
 * a alternativa seria um índice cego (HMAC em coluna à parte), que vaza exatamente o mesmo: que
 * dois registros têm o mesmo valor. Aqui o IV é sintético (construção SIV): os 12 primeiros bytes
 * do HMAC-SHA256 do próprio texto. O mesmo texto dá a mesma cifra, então consulta, unicidade e o
 * conversor do Hibernate (que cifra também os parâmetros da consulta) seguem funcionando. Textos
 * diferentes têm IV diferentes (colisão em 96 bits só perto de 2^48 valores), o que afasta o
 * perigo do GCM, que é reusar o IV com texto diferente.
 *
 * <p><strong>Chaves.</strong> Da chave mestra de 32 bytes ({@code CRIPTO_CHAVE}) saem, por HMAC,
 * uma chave de cifra e uma de IV — nunca a mesma chave para as duas funções. Na decifragem o IV é
 * recalculado e conferido (integridade além da tag do GCM).
 *
 * <p><strong>Formato.</strong> {@code v1:} + Base64url(IV ‖ cifra ‖ tag). O prefixo é a versão da
 * chave: a rotação futura é uma {@code v2} lida ao lado da {@code v1}. Valor sem o prefixo é
 * <em>legado</em> (gravado antes da #147) e volta como está, até o {@code CifragemLegadoRunner}
 * cifrá-lo na subida.
 *
 * <p>Erro de decifragem (chave errada, dado adulterado) lança — nunca devolve lixo nem o valor
 * cifrado como se fosse o dado. A mensagem não leva o valor.
 */
public final class CifraDeterministica {

  private static final Logger log = LoggerFactory.getLogger(CifraDeterministica.class);

  /** Prefixo da versão da chave em todo valor cifrado. */
  public static final String PREFIXO = "v1:";

  /**
   * Chave fixa de desenvolvimento e testes. <strong>Pública</strong> — está no repositório: dado
   * cifrado com ela não está protegido. Fora de {@code dev}/{@code test} a aplicação a recusa.
   */
  static final String CHAVE_DE_DESENVOLVIMENTO = "ZGV2LWFncm9jb250YWJpbC1jaGF2ZS1wdWJsaWNhLTM=";

  private static final int TAMANHO_CHAVE = 32;
  private static final int TAMANHO_IV = 12;
  private static final int TAMANHO_TAG_BITS = 128;
  private static final String HMAC = "HmacSHA256";
  private static final String AES_GCM = "AES/GCM/NoPadding";

  private final SecretKeySpec chaveCifra;
  private final SecretKeySpec chaveIv;

  private CifraDeterministica(byte[] mestra) {
    this.chaveCifra = new SecretKeySpec(
        DerivacaoChave.derivar(mestra, "agrocontabil:cripto:cifra:v1"), "AES");
    this.chaveIv = new SecretKeySpec(
        DerivacaoChave.derivar(mestra, "agrocontabil:cripto:iv:v1"), HMAC);
  }

  /**
   * Monta a cifra a partir da configuração, recusando chave ausente, curta ou de desenvolvimento
   * fora de dev/test.
   *
   * @param propriedades configuração
   * @return a cifra
   * @throws IllegalStateException se a chave não servir
   */
  public static CifraDeterministica de(CriptografiaProperties propriedades) {
    byte[] mestra = mestra(propriedades, true);
    try {
      return new CifraDeterministica(mestra);
    } finally {
      Arrays.fill(mestra, (byte) 0);
    }
  }

  /**
   * Chave derivada da mesma {@code CRIPTO_CHAVE}, para outro uso (#499: a assinatura do histórico
   * da Ceres), com as mesmas recusas de {@link #de} e a mesma derivação ({@link DerivacaoChave}).
   *
   * @param propriedades configuração
   * @param rotulo       rótulo da derivação (um por uso)
   * @return 32 bytes da chave derivada
   * @throws IllegalStateException se a chave não servir
   */
  public static byte[] chaveDerivada(CriptografiaProperties propriedades, String rotulo) {
    byte[] mestra = mestra(propriedades, false);
    try {
      return DerivacaoChave.derivar(mestra, rotulo);
    } finally {
      Arrays.fill(mestra, (byte) 0);
    }
  }

  /**
   * A chave mestra, com as recusas: ausente ou de desenvolvimento fora de dev/test, tamanho
   * diferente de 32 bytes.
   *
   * @param avisar {@code true} para o WARN da chave de desenvolvimento (só uma vez, na cifra)
   */
  private static byte[] mestra(CriptografiaProperties propriedades, boolean avisar) {
    String chave = propriedades.chave();
    boolean ausente = chave == null || chave.isBlank();
    // Pelos bytes, nao pelo texto: o decodificador aceita Base64 sem o "=" final, e a chave de
    // desenvolvimento sem padding passaria por uma comparacao de String.
    boolean deDesenvolvimento = !ausente && MessageDigest.isEqual(decodificar(chave),
        decodificar(CHAVE_DE_DESENVOLVIMENTO));
    if (ausente || deDesenvolvimento) {
      if (!propriedades.permitirChaveDeDesenvolvimento()) {
        throw new IllegalStateException(deDesenvolvimento
            ? "CRIPTO_CHAVE e a chave publica de desenvolvimento: gere uma chave aleatoria."
            : "CRIPTO_CHAVE ausente: defina 32 bytes aleatorios em Base64 (openssl rand -base64 32).");
      }
      if (avisar) {
        log.warn("CRIPTO_CHAVE {}: usando a chave publica de desenvolvimento. Dados pessoais do "
            + "banco NAO estao protegidos.", ausente ? "ausente" : "de desenvolvimento");
      }
      chave = CHAVE_DE_DESENVOLVIMENTO;
    }
    byte[] mestra = decodificar(chave);
    if (mestra.length != TAMANHO_CHAVE) {
      throw new IllegalStateException("CRIPTO_CHAVE precisa ter exatamente " + TAMANHO_CHAVE
          + " bytes (AES-256); tem " + mestra.length + ".");
    }
    return mestra;
  }

  /**
   * Cifra um valor.
   *
   * @param texto valor em claro
   * @return {@code v1:...}; nulo volta nulo
   */
  public String cifrar(String texto) {
    if (texto == null) {
      return null;
    }
    byte[] claro = texto.getBytes(StandardCharsets.UTF_8);
    try {
      byte[] iv = ivDe(claro);
      Cipher cifra = Cipher.getInstance(AES_GCM);
      cifra.init(Cipher.ENCRYPT_MODE, chaveCifra, new GCMParameterSpec(TAMANHO_TAG_BITS, iv));
      byte[] cifrado = cifra.doFinal(claro);
      byte[] saida = new byte[TAMANHO_IV + cifrado.length];
      System.arraycopy(iv, 0, saida, 0, TAMANHO_IV);
      System.arraycopy(cifrado, 0, saida, TAMANHO_IV, cifrado.length);
      return PREFIXO + Base64.getUrlEncoder().withoutPadding().encodeToString(saida);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Falha ao cifrar dado pessoal", e);
    }
  }

  /**
   * Decifra um valor. Valor sem o prefixo é legado em claro e volta como está.
   *
   * @param valor valor do banco
   * @return o texto em claro; nulo volta nulo
   * @throws IllegalStateException se o valor cifrado não conferir (chave errada, adulteração)
   */
  public String decifrar(String valor) {
    if (valor == null || !valor.startsWith(PREFIXO)) {
      return valor;
    }
    try {
      byte[] entrada = Base64.getUrlDecoder().decode(valor.substring(PREFIXO.length()));
      if (entrada.length < TAMANHO_IV + TAMANHO_TAG_BITS / 8) {
        throw new IllegalStateException("Dado pessoal cifrado invalido");
      }
      byte[] iv = Arrays.copyOfRange(entrada, 0, TAMANHO_IV);
      Cipher cifra = Cipher.getInstance(AES_GCM);
      cifra.init(Cipher.DECRYPT_MODE, chaveCifra, new GCMParameterSpec(TAMANHO_TAG_BITS, iv));
      byte[] claro = cifra.doFinal(entrada, TAMANHO_IV, entrada.length - TAMANHO_IV);
      if (!MessageDigest.isEqual(iv, ivDe(claro))) {
        throw new IllegalStateException("Dado pessoal cifrado invalido");
      }
      return new String(claro, StandardCharsets.UTF_8);
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      // Sem o valor na mensagem nem na causa encadeada como texto.
      throw new IllegalStateException("Dado pessoal cifrado invalido ou chave errada");
    }
  }

  /**
   * Diz se o valor já está cifrado (tem o prefixo de versão).
   *
   * @param valor valor do banco
   * @return {@code true} se cifrado
   */
  public static boolean estaCifrado(String valor) {
    return valor != null && valor.startsWith(PREFIXO);
  }

  private static byte[] decodificar(String chave) {
    try {
      return Base64.getDecoder().decode(chave.trim());
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException("CRIPTO_CHAVE nao e Base64 valido.");
    }
  }

  private byte[] ivDe(byte[] claro) throws GeneralSecurityException {
    Mac mac = Mac.getInstance(HMAC);
    mac.init(chaveIv);
    return Arrays.copyOf(mac.doFinal(claro), TAMANHO_IV);
  }
}
