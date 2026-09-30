package br.com.agrocontabil.assistente.rag;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

/**
 * Lê o HTML de uma norma salvo do Planalto (issue #156).
 *
 * <p>O charset não pode vir da declaração do documento: o texto compilado da LC 214/2025 <strong>não
 * declara charset</strong> e está em windows-1252. Sem declaração, o jsoup assume UTF-8 e corrompe
 * "º", "§", "ç" e "ã" — a detecção de "Art. 1º" e "§ 1º" falha e o texto gravado fica ilegível.
 *
 * <p>Regra: bytes que são UTF-8 válido são lidos como UTF-8; os demais, como windows-1252 (o
 * charset do Planalto, superconjunto do ISO-8859-1 nos caracteres imprimíveis). Um texto em
 * windows-1252 com acentos quase nunca é UTF-8 válido por acaso.
 */
final class LeitorHtmlLegislacao {

  /** Charset das páginas do Planalto. */
  static final String WINDOWS_1252 = "windows-1252";

  private LeitorHtmlLegislacao() {
  }

  /**
   * Lê e interpreta o arquivo.
   *
   * @param arquivo HTML salvo do Planalto
   * @return documento com o texto decodificado corretamente
   * @throws IOException se o arquivo não puder ser lido
   */
  static Document ler(Path arquivo) throws IOException {
    byte[] bytes = Files.readAllBytes(arquivo);
    return Jsoup.parse(new ByteArrayInputStream(bytes), charset(bytes), arquivo.toUri().toString());
  }

  /** {@code UTF-8} se os bytes forem UTF-8 válido; senão {@code windows-1252}. */
  static String charset(byte[] bytes) {
    try {
      StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes));
      return StandardCharsets.UTF_8.name();
    } catch (CharacterCodingException e) {
      return WINDOWS_1252;
    }
  }
}
