package br.com.agrocontabil.nfe;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.exception.AssinaturaDigitalException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import javax.xml.parsers.DocumentBuilder;
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
      if (keyInfo == null) {
        throw new AssinaturaDigitalException(ErrorCode.ASSINATURA_INVALIDA,
            "Assinatura sem KeyInfo");
      }
      X509Certificate cert = keyInfo.getX509Certificate();
      PublicKey publicKey = cert != null ? cert.getPublicKey() : keyInfo.getPublicKey();
      if (publicKey == null) {
        throw new AssinaturaDigitalException(ErrorCode.ASSINATURA_INVALIDA,
            "Assinatura sem chave publica no KeyInfo (X509Certificate/PublicKey ausentes)");
      }
      boolean valido = cert != null
          ? signature.checkSignatureValue(cert)
          : signature.checkSignatureValue(publicKey);

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
    DocumentBuilder db = SecureXmlFactories.hardenedDocumentBuilderFactory().newDocumentBuilder();
    return db.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
  }
}
