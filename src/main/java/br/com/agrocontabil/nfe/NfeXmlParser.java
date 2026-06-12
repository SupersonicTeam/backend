package br.com.agrocontabil.nfe;

import br.com.agrocontabil.exception.XmlImportacaoException;
import br.com.agrocontabil.nfe.model.NFe;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Unmarshaller;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Faz o parse de um XML de NF-e importado para o grafo JAXB {@link NFe}.
 *
 * <p>Pipeline: parse DOM com hardening XXE → localiza o elemento {@code <NFe>}
 * (raiz {@code <nfeProc>} de arquivo autorizado, ou {@code <NFe>} puro) →
 * serializa o nó e valida contra o XSD via {@link XsdValidator} → unmarshal JAXB.
 *
 * <p>Qualquer falha (malformado, sem {@code <NFe>}, XSD inválido) lança
 * {@link XmlImportacaoException}.
 */
@Component
public class NfeXmlParser {

  private static final Logger log = LoggerFactory.getLogger(NfeXmlParser.class);
  private static final String NFE_NS = "http://www.portalfiscal.inf.br/nfe";

  private final XsdValidator xsdValidator;

  public NfeXmlParser(XsdValidator xsdValidator) {
    this.xsdValidator = xsdValidator;
  }

  /**
   * Parseia o XML importado e retorna o grafo JAXB da NF-e.
   *
   * @param xmlBytes conteúdo bruto do arquivo XML
   * @return grafo {@link NFe} extraído e validado contra o XSD
   * @throws XmlImportacaoException se o XML for inválido ou não contiver {@code <NFe>}
   */
  public NFe parse(byte[] xmlBytes) {
    Document doc = parseSeguro(xmlBytes);

    NodeList nfeNodes = doc.getElementsByTagNameNS(NFE_NS, "NFe");
    if (nfeNodes.getLength() == 0) {
      throw new XmlImportacaoException(
          "XML nao contem o elemento <NFe> no namespace da NF-e.");
    }
    Node nfeNode = nfeNodes.item(0);

    String nfeXml = serializar(nfeNode);

    // Valida o <NFe> contra o XSD oficial (XsdValidator tolera ausencia de assinatura).
    xsdValidator.validar(nfeXml);

    return unmarshal(nfeNode);
  }

  private Document parseSeguro(byte[] xmlBytes) {
    try {
      DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
      dbf.setNamespaceAware(true);
      // Hardening XXE
      dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
      dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      dbf.setXIncludeAware(false);
      dbf.setExpandEntityReferences(false);
      DocumentBuilder builder = dbf.newDocumentBuilder();
      return builder.parse(new ByteArrayInputStream(xmlBytes));
    } catch (Exception e) {
      log.warn("Falha ao parsear XML importado: {}", e.getMessage());
      throw new XmlImportacaoException("XML invalido ou malformado: " + e.getMessage(), e);
    }
  }

  private String serializar(Node node) {
    try {
      TransformerFactory tf = TransformerFactory.newInstance();
      tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
      Transformer transformer = tf.newTransformer();
      transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
      StringWriter writer = new StringWriter();
      transformer.transform(new DOMSource(node), new StreamResult(writer));
      return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + writer;
    } catch (Exception e) {
      throw new XmlImportacaoException("Falha ao serializar o elemento NFe: " + e.getMessage(), e);
    }
  }

  private NFe unmarshal(Node nfeNode) {
    try {
      JAXBContext context = JAXBContext.newInstance(NFe.class);
      Unmarshaller unmarshaller = context.createUnmarshaller();
      return (NFe) unmarshaller.unmarshal(nfeNode);
    } catch (Exception e) {
      throw new XmlImportacaoException("Falha ao desserializar a NF-e (JAXB): " + e.getMessage(), e);
    }
  }
}
