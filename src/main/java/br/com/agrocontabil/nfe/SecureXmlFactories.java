package br.com.agrocontabil.nfe;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.stream.XMLInputFactory;
import javax.xml.transform.TransformerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fábricas JAXP endurecidas contra XXE, usadas pela assinatura/validação da NF-e.
 *
 * <p>Centraliza o hardening (DRY) e é defensiva: cada feature/atributo é aplicado em
 * try/catch porque provedores JAXP alternativos no classpath podem não suportar
 * {@code ACCESS_EXTERNAL_*} ou certas features (lançam {@code IllegalArgumentException}
 * ou {@code ParserConfigurationException}). Um downgrade é logado em WARN, nunca silencioso.
 */
final class SecureXmlFactories {

  private static final Logger log = LoggerFactory.getLogger(SecureXmlFactories.class);

  private static final String DISALLOW_DOCTYPE =
      "http://apache.org/xml/features/disallow-doctype-decl";
  private static final String EXTERNAL_GENERAL_ENTITIES =
      "http://xml.org/sax/features/external-general-entities";
  private static final String EXTERNAL_PARAMETER_ENTITIES =
      "http://xml.org/sax/features/external-parameter-entities";
  private static final String LOAD_EXTERNAL_DTD =
      "http://apache.org/xml/features/nonvalidating/load-external-dtd";

  private SecureXmlFactories() {}

  /** Cria um {@link DocumentBuilderFactory} namespace-aware com hardening XXE completo. */
  static DocumentBuilderFactory hardenedDocumentBuilderFactory() {
    DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
    dbf.setNamespaceAware(true);
    setFeature(dbf, XMLConstants.FEATURE_SECURE_PROCESSING, true);
    setFeature(dbf, DISALLOW_DOCTYPE, true);
    setFeature(dbf, EXTERNAL_GENERAL_ENTITIES, false);
    setFeature(dbf, EXTERNAL_PARAMETER_ENTITIES, false);
    setFeature(dbf, LOAD_EXTERNAL_DTD, false);
    dbf.setXIncludeAware(false);
    dbf.setExpandEntityReferences(false);
    setAttribute(dbf, XMLConstants.ACCESS_EXTERNAL_DTD, "");
    setAttribute(dbf, XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    return dbf;
  }

  /**
   * Cria um {@link XMLInputFactory} StAX endurecido contra XXE, usado no unmarshalling de
   * XML importado (issue #19).
   *
   * <p>Desativa a resolucao de DTD ({@code SUPPORT_DTD=false}) e a expansao de entidades
   * externas ({@code IS_SUPPORTING_EXTERNAL_ENTITIES=false}). Com o suporte a DTD desligado,
   * qualquer {@code <!DOCTYPE ...>} no documento provoca erro de parsing — o vetor classico
   * de XXE (entidade externa declarada em DTD interno/externo) fica bloqueado na origem.
   * Cada propriedade e aplicada defensivamente: provedores StAX alternativos podem nao
   * suporta-las (logamos WARN, nunca silenciamos).
   */
  static XMLInputFactory hardenedXmlInputFactory() {
    XMLInputFactory xif = XMLInputFactory.newFactory();
    // Defesa primaria: sem DTD nenhuma entidade externa pode ser declarada.
    setStaxProperty(xif, XMLInputFactory.SUPPORT_DTD, false);
    // Defesa em profundidade: mesmo que o provider ignore o acima, nao resolve externas.
    setStaxProperty(xif, XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    return xif;
  }

  /** Cria um {@link TransformerFactory} com hardening (secure processing + sem externos). */
  static TransformerFactory hardenedTransformerFactory() {
    TransformerFactory tf = TransformerFactory.newInstance();
    try {
      tf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    } catch (Exception e) {
      log.warn("SECURITY: TransformerFactory nao suporta FEATURE_SECURE_PROCESSING: {}",
          e.getMessage());
    }
    setTransformerAttribute(tf, XMLConstants.ACCESS_EXTERNAL_DTD, "");
    setTransformerAttribute(tf, XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
    return tf;
  }

  private static void setFeature(DocumentBuilderFactory dbf, String feature, boolean value) {
    try {
      dbf.setFeature(feature, value);
    } catch (ParserConfigurationException e) {
      log.warn("SECURITY: DocumentBuilderFactory nao suporta a feature '{}': {}",
          feature, e.getMessage());
    }
  }

  private static void setStaxProperty(XMLInputFactory xif, String name, boolean value) {
    try {
      xif.setProperty(name, value);
    } catch (IllegalArgumentException e) {
      log.warn("SECURITY: XMLInputFactory nao suporta a propriedade '{}': {}",
          name, e.getMessage());
    }
  }

  private static void setAttribute(DocumentBuilderFactory dbf, String name, String value) {
    try {
      dbf.setAttribute(name, value);
    } catch (IllegalArgumentException e) {
      log.warn("SECURITY: DocumentBuilderFactory nao suporta o atributo '{}': {}",
          name, e.getMessage());
    }
  }

  private static void setTransformerAttribute(TransformerFactory tf, String name, String value) {
    try {
      tf.setAttribute(name, value);
    } catch (IllegalArgumentException e) {
      log.warn("SECURITY: TransformerFactory nao suporta o atributo '{}': {}",
          name, e.getMessage());
    }
  }
}
