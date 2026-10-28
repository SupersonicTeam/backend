package br.com.agrocontabil.nfe;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

/**
 * DigestValue da assinatura de um XML de NF-e (issue #223).
 *
 * <p>A SEFAZ devolve no protocolo ({@code infProt/digVal}) o DigestValue do XML que ela autorizou.
 * Comparar com o do XML recebido prova que o conteúdo é o autorizado: a assinatura sozinha não
 * basta, porque qualquer certificado (até autoassinado) assina um XML forjado com a chave de uma
 * NF-e real.
 *
 * <p><strong>Pelo DOM, não pelo texto.</strong> Só conta o {@code DigestValue} no namespace da
 * assinatura (XML-DSig), e só quando há exatamente um. Uma busca no texto pegaria o primeiro
 * {@code <DigestValue>} que aparecesse — e um XML forjado leva, num {@code CDATA} do
 * {@code infCpl} (que vem antes da assinatura), o digest da NF-e verdadeira, passando na
 * conferência com o conteúdo trocado. Texto e {@code CDATA} não são elementos no DOM.
 */
public final class DigestAssinatura {

  /** Namespace da assinatura XML (XML-DSig). */
  private static final String XMLDSIG_NS = "http://www.w3.org/2000/09/xmldsig#";

  private DigestAssinatura() {}

  /**
   * DigestValue da (única) Reference da assinatura do XML.
   *
   * @param xml XML assinado
   * @return o valor em Base64, ou vazio se não houver exatamente um {@code ds:DigestValue} ou o
   *     XML não for legível
   */
  public static Optional<String> extrair(String xml) {
    if (xml == null || xml.isBlank()) {
      return Optional.empty();
    }
    try {
      Document doc = SecureXmlFactories.hardenedDocumentBuilderFactory().newDocumentBuilder()
          .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
      NodeList digests = doc.getElementsByTagNameNS(XMLDSIG_NS, "DigestValue");
      if (digests.getLength() != 1) {
        return Optional.empty();
      }
      String valor = digests.item(0).getTextContent().strip();
      return valor.isEmpty() ? Optional.empty() : Optional.of(valor);
    } catch (Exception e) {
      return Optional.empty();
    }
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
