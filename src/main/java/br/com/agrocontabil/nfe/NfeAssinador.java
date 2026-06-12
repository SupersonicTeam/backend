package br.com.agrocontabil.nfe;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.exception.AssinaturaDigitalException;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.apache.xml.security.Init;
import org.apache.xml.security.c14n.Canonicalizer;
import org.apache.xml.security.signature.XMLSignature;
import org.apache.xml.security.transforms.Transforms;
import org.apache.xml.security.utils.Constants;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Assina digitalmente o XML da NF-e (assinatura enveloped no {@code infNFe}),
 * conforme o padrão SEFAZ (C14N inclusiva, RSA-SHA1, SHA-1, X509Data).
 *
 * <p>Parser DOM com hardening XXE (mesmo padrão do {@code XsdValidator}).
 */
@Component
public class NfeAssinador {

  private static final String NFE_NS = "http://www.portalfiscal.inf.br/nfe";

  static {
    if (!Init.isInitialized()) {
      Init.init();
    }
  }

  private final KeyStoreCertificadoProvider certificadoProvider;

  public NfeAssinador(KeyStoreCertificadoProvider certificadoProvider) {
    this.certificadoProvider = certificadoProvider;
  }

  /**
   * Assina o XML da NF-e.
   *
   * @param xml      XML não assinado (saída do {@code XmlGenerator})
   * @param infNfeId valor do atributo {@code Id} do elemento {@code infNFe}
   * @return XML assinado (com o nó {@code Signature} dentro de {@code NFe})
   */
  public String assinar(String xml, String infNfeId) {
    try {
      Document doc = parseSeguro(xml);

      Element infNFe = (Element) doc.getElementsByTagNameNS(NFE_NS, "infNFe").item(0);
      if (infNFe == null) {
        throw new AssinaturaDigitalException(ErrorCode.ASSINATURA_FALHOU,
            "Elemento infNFe nao encontrado no XML");
      }
      infNFe.setIdAttributeNS(null, "Id", true);

      Element nfe = (Element) infNFe.getParentNode();

      XMLSignature signature = new XMLSignature(
          doc, "",
          XMLSignature.ALGO_ID_SIGNATURE_RSA_SHA1,
          Canonicalizer.ALGO_ID_C14N_OMIT_COMMENTS);
      // Signature como irmão de infNFe (último filho de NFe)
      nfe.appendChild(signature.getElement());

      Transforms transforms = new Transforms(doc);
      transforms.addTransform(Transforms.TRANSFORM_ENVELOPED_SIGNATURE);
      transforms.addTransform(Transforms.TRANSFORM_C14N_OMIT_COMMENTS);
      signature.addDocument("#" + infNfeId, transforms,
          Constants.ALGO_ID_DIGEST_SHA1);

      signature.addKeyInfo(certificadoProvider.getCertificate());
      signature.sign(certificadoProvider.getPrivateKey());

      return serializar(doc);
    } catch (AssinaturaDigitalException e) {
      throw e;
    } catch (Exception e) {
      throw new AssinaturaDigitalException(ErrorCode.ASSINATURA_FALHOU,
          "Falha ao assinar o XML da NF-e: " + e.getMessage(), e);
    }
  }

  private Document parseSeguro(String xml) throws Exception {
    DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
    dbf.setNamespaceAware(true);
    // Hardening XXE (mesmo padrão do XsdValidator)
    dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
    dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    DocumentBuilder db = dbf.newDocumentBuilder();
    return db.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
  }

  private String serializar(Document doc) throws Exception {
    TransformerFactory tf = TransformerFactory.newInstance();
    tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
    Transformer transformer = tf.newTransformer();
    transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
    transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
    StringWriter writer = new StringWriter();
    transformer.transform(new DOMSource(doc), new StreamResult(writer));
    return writer.toString();
  }
}
