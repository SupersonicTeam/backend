package br.com.agrocontabil.nfe;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DigestValue da assinatura de um XML de NF-e (issue #223).
 *
 * <p>A SEFAZ devolve no protocolo ({@code infProt/digVal}) o DigestValue do XML que ela autorizou.
 * Comparar com o do XML recebido prova que o conteúdo é o autorizado: a assinatura sozinha não
 * basta, porque qualquer certificado (até autoassinado) assina um XML forjado com a chave de uma
 * NF-e real.
 */
public final class DigestAssinatura {

  /** DigestValue da Reference, com ou sem prefixo de namespace ({@code ds:}). */
  private static final Pattern DIGEST_VALUE =
      Pattern.compile("<(?:\\w+:)?DigestValue>\\s*([^<\\s]+)\\s*</(?:\\w+:)?DigestValue>");

  private DigestAssinatura() {}

  /**
   * DigestValue da (única) Reference da assinatura do XML.
   *
   * @param xml XML assinado
   * @return o valor em Base64, ou vazio se o XML não tiver assinatura
   */
  public static Optional<String> extrair(String xml) {
    if (xml == null) {
      return Optional.empty();
    }
    Matcher digest = DIGEST_VALUE.matcher(xml);
    return digest.find() ? Optional.of(digest.group(1)) : Optional.empty();
  }

  /**
   * O XML é o que a SEFAZ autorizou.
   *
   * @param xml    XML assinado recebido
   * @param digVal {@code digVal} do protocolo da SEFAZ; nulo quando o transporte não o informa
   * @return {@code true} se o digVal é nulo (nada a conferir) ou igual ao DigestValue do XML
   */
  public static boolean confere(String xml, String digVal) {
    if (digVal == null || digVal.isBlank()) {
      return true;
    }
    return extrair(xml).map(digVal.strip()::equals).orElse(false);
  }
}
