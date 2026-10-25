package br.com.agrocontabil.service;

import br.com.agrocontabil.exception.PendenciaException;
import br.com.agrocontabil.model.PendenciaAnexo;
import br.com.agrocontabil.nfe.SecureXmlFactories;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Confere o arquivo de um anexo de pendência (issue #180): tamanho, tipo pelos bytes iniciais
 * (nunca pelo nome nem pelo {@code Content-Type} declarado), XML bem formado sem entidade externa,
 * nome sem caminho nem caractere de controle, e o SHA-256.
 */
final class ConferenciaAnexo {

  static final String PDF = "application/pdf";
  static final String JPEG = "image/jpeg";
  static final String PNG = "image/png";
  static final String XML = "application/xml";

  /** Tamanho máximo do nome guardado. */
  static final int NOME_MAXIMO = 120;

  private static final byte[] ASSINATURA_PDF = {'%', 'P', 'D', 'F', '-'};
  private static final byte[] ASSINATURA_JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
  private static final byte[] ASSINATURA_PNG =
      {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

  private ConferenciaAnexo() {
  }

  /**
   * MIME conferido pelos bytes.
   *
   * @param bytes conteúdo
   * @return o MIME
   * @throws PendenciaException vazio, acima de 5 MB ou de tipo não aceito (400)
   */
  static String mimeConferido(byte[] bytes) {
    if (bytes == null || bytes.length == 0) {
      throw PendenciaException.anexoInvalido("Arquivo vazio.");
    }
    if (bytes.length > PendenciaAnexo.TAMANHO_MAXIMO) {
      throw PendenciaException.anexoInvalido("Arquivo acima de 5 MB.");
    }
    if (comeca(bytes, ASSINATURA_PDF)) {
      return PDF;
    }
    if (comeca(bytes, ASSINATURA_JPEG)) {
      return JPEG;
    }
    if (comeca(bytes, ASSINATURA_PNG)) {
      return PNG;
    }
    if (pareceXml(bytes)) {
      conferirXml(bytes);
      return XML;
    }
    throw PendenciaException.anexoInvalido("Tipo de arquivo nao aceito.");
  }

  /**
   * Nome do arquivo sem caminho, sem caractere de controle, até 120 caracteres.
   *
   * @param original nome enviado pelo cliente
   * @return nome sanitizado (nunca vazio)
   */
  static String nomeSanitizado(String original) {
    String nome = original == null ? "" : original;
    nome = nome.substring(Math.max(nome.lastIndexOf('/'), nome.lastIndexOf('\\')) + 1);
    nome = nome.replaceAll("\\p{Cntrl}", "").replace("\"", "").strip();
    if (nome.isEmpty() || nome.equals(".") || nome.equals("..")) {
      nome = "anexo";
    }
    return nome.length() > NOME_MAXIMO ? nome.substring(nome.length() - NOME_MAXIMO) : nome;
  }

  /**
   * SHA-256 do conteúdo em hexadecimal.
   *
   * @param bytes conteúdo
   * @return hash
   */
  static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 indisponivel", e);
    }
  }

  private static boolean comeca(byte[] bytes, byte[] assinatura) {
    if (bytes.length < assinatura.length) {
      return false;
    }
    for (int i = 0; i < assinatura.length; i++) {
      if (bytes[i] != assinatura[i]) {
        return false;
      }
    }
    return true;
  }

  /** Primeiro caractere útil é {@code <} (depois do BOM UTF-8 e de espaços). */
  private static boolean pareceXml(byte[] bytes) {
    int i = 0;
    if (bytes.length >= 3 && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB
        && bytes[2] == (byte) 0xBF) {
      i = 3;
    }
    while (i < bytes.length && Character.isWhitespace(bytes[i])) {
      i++;
    }
    return i < bytes.length && bytes[i] == '<';
  }

  /** XML bem formado, pelo parser endurecido do projeto (sem DTD nem entidade externa). */
  private static void conferirXml(byte[] bytes) {
    try {
      SecureXmlFactories.hardenedDocumentBuilderFactory().newDocumentBuilder()
          .parse(new ByteArrayInputStream(bytes));
    } catch (Exception e) {
      throw PendenciaException.anexoInvalido("XML mal formado ou com DTD.");
    }
  }
}
