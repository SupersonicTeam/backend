package br.com.agrocontabil.nfe;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.exception.AssinaturaDigitalException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.xml.security.Init;
import org.apache.xml.security.keys.KeyInfo;
import org.apache.xml.security.signature.XMLSignature;
import org.apache.xml.security.utils.Constants;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Valida criptograficamente a assinatura de um XML de NF-e (core validation:
 * confere SignatureValue e o DigestValue da Reference). Detecta adulteração.
 *
 * <p>Parser DOM com hardening XXE.
 */
@Component
public class AssinaturaValidator {

  private static final String NFE_NS = "http://www.portalfiscal.inf.br/nfe";

  static {
    if (!Init.isInitialized()) {
      Init.init();
    }
  }

  /**
   * Valida a assinatura do XML.
   *
   * @param xmlAssinado XML com o nó {@code Signature}
   * @throws AssinaturaDigitalException se a assinatura estiver ausente ou inválida
   */
  public void validar(String xmlAssinado) {
    try {
      Document doc = parseSeguro(xmlAssinado);

      Element infNFe = (Element) doc.getElementsByTagNameNS(NFE_NS, "infNFe").item(0);
      if (infNFe != null) {
        infNFe.setIdAttributeNS(null, "Id", true);
      }

      Element sigElement = (Element) doc
          .getElementsByTagNameNS(Constants.SignatureSpecNS, "Signature").item(0);
      if (sigElement == null) {
        throw new AssinaturaDigitalException(ErrorCode.ASSINATURA_INVALIDA,
            "XML sem elemento Signature");
      }

      XMLSignature signature = new XMLSignature(sigElement, "");
      KeyInfo keyInfo = signature.getKeyInfo();
      X509Certificate cert = keyInfo == null ? null : keyInfo.getX509Certificate();
      boolean valido = cert != null
          ? signature.checkSignatureValue(cert)
          : signature.checkSignatureValue(keyInfo == null ? null : keyInfo.getPublicKey());

      if (!valido) {
        throw new AssinaturaDigitalException(ErrorCode.ASSINATURA_INVALIDA,
            "Assinatura digital invalida (SignatureValue/DigestValue nao conferem)");
      }
    } catch (AssinaturaDigitalException e) {
      throw e;
    } catch (Exception e) {
      throw new AssinaturaDigitalException(ErrorCode.ASSINATURA_INVALIDA,
          "Falha ao validar a assinatura: " + e.getMessage(), e);
    }
  }

  private Document parseSeguro(String xml) throws Exception {
    DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
    dbf.setNamespaceAware(true);
    dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
    dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    DocumentBuilder db = dbf.newDocumentBuilder();
    return db.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
  }
}
