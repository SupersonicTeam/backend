package br.com.agrocontabil.nfe;

import br.com.agrocontabil.exception.XmlGenerationException;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.transform.Source;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.ls.LSInput;
import org.w3c.dom.ls.LSResourceResolver;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * Validador do XML da NF-e contra o XSD oficial {@code nfe_v4.00.xsd}.
 *
 * <h3>Segurança (XXE prevention):</h3>
 * <ul>
 *   <li>{@code XMLConstants.FEATURE_SECURE_PROCESSING} ativado na {@link SchemaFactory}</li>
 *   <li>{@code ACCESS_EXTERNAL_DTD} e {@code ACCESS_EXTERNAL_SCHEMA} definidos como
 *       string vazia na {@link SchemaFactory} — bloqueia resolução de DTD e schemas externos</li>
 *   <li>Um {@link LSResourceResolver} interno resolve apenas os imports relativos conhecidos
 *       do classpath ({@code leiauteNFe_v4.00.xsd}, {@code tiposBasico_v4.00.xsd},
 *       {@code DFeTiposBasicos_v1.00.xsd}, {@code xmldsig-core-schema_v1.01.xsd}),
 *       bloqueando qualquer outra resolução</li>
 * </ul>
 *
 * <h3>Resolução de imports relativos:</h3>
 * <p>{@code nfe_v4.00.xsd} faz {@code xs:include} de {@code leiauteNFe_v4.00.xsd},
 * que por sua vez faz imports relativos de {@code tiposBasico_v4.00.xsd},
 * {@code DFeTiposBasicos_v1.00.xsd} e {@code xmldsig-core-schema_v1.01.xsd}.
 * O {@link ClasspathLsResourceResolver} intercepta as chamadas do parser e entrega
 * os recursos do classpath ({@code /xsd/}), sem depender de URLs absolutas.
 *
 * <h3>Schema em cache:</h3>
 * <p>O {@link Schema} é construído uma única vez e reutilizado — {@link Schema#newValidator()}
 * é chamado a cada validação (thread-safe por design da API JAXP).
 */
@Component
public class XsdValidator {

  private static final Logger log = LoggerFactory.getLogger(XsdValidator.class);
  private static final String XSD_ROOT = "/xsd/nfe_v4.00.xsd";

  private volatile Schema schema;
  private final Object schemaLock = new Object();

  /**
   * Valida o XML da NF-e contra o XSD oficial.
   *
   * @param xml string XML gerada pelo {@link XmlGenerator}
   * @throws XmlGenerationException se o XML não for válido, com lista de erros formatados
   */
  public void validate(String xml) {
    Schema s = getSchema();
    Validator validator = s.newValidator();

    // XXE: desativa resolução de entidades externas no validador
    try {
      validator.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    } catch (SAXException ignored) {
      // feature não suportada por alguma impl — continua sem ela
    }

    List<String> errors = new ArrayList<>();
    validator.setErrorHandler(new ErrorHandler() {
      @Override
      public void warning(SAXParseException e) {
        log.warn("XSD warning linha={} col={}: {}", e.getLineNumber(), e.getColumnNumber(),
            e.getMessage());
      }

      @Override
      public void error(SAXParseException e) {
        errors.add(formatError(e));
      }

      @Override
      public void fatalError(SAXParseException e) {
        errors.add("[FATAL] " + formatError(e));
      }
    });

    try {
      Source source = new StreamSource(new StringReader(xml));
      validator.validate(source);
    } catch (SAXException | IOException e) {
      if (errors.isEmpty()) {
        errors.add("Erro ao processar o XML: " + e.getMessage());
      }
    }

    // A NF-e é assinada digitalmente (ds:Signature) antes da validação XSD — todo error
    // de validação é fatal (a tolerância da Sprint 2 à ausência de assinatura foi removida
    // quando a assinatura ICP-Brasil/sandbox passou a integrar o pipeline de geração).
    if (!errors.isEmpty()) {
      String detail = String.join("; ", errors);
      log.warn("XML da NF-e invalido contra o XSD: {}", detail);
      throw new XmlGenerationException(
          "XML da NF-e invalido: " + detail);
    }
  }

  // =========================================================================
  // Schema cache
  // =========================================================================

  private Schema getSchema() {
    if (schema != null) {
      return schema;
    }
    synchronized (schemaLock) {
      if (schema != null) {
        return schema;
      }
      schema = buildSchema();
    }
    return schema;
  }

  private Schema buildSchema() {
    try {
      SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);

      // XXE prevention na SchemaFactory
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      safeSetProperty(factory, XMLConstants.ACCESS_EXTERNAL_DTD, "");
      safeSetProperty(factory, XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

      // Resolver de imports relativos via classpath
      factory.setResourceResolver(new ClasspathLsResourceResolver());

      URL xsdUrl = XsdValidator.class.getResource(XSD_ROOT);
      if (xsdUrl == null) {
        throw new XmlGenerationException(
            "XSD nfe_v4.00.xsd nao encontrado no classpath em /xsd/");
      }
      return factory.newSchema(xsdUrl);
    } catch (SAXException e) {
      throw new XmlGenerationException("Falha ao construir Schema XSD: " + e.getMessage(), e);
    }
  }

  private void safeSetProperty(SchemaFactory factory, String name, String value) {
    try {
      factory.setProperty(name, value);
    } catch (SAXException e) {
      // SEGURANÇA: ACCESS_EXTERNAL_DTD/SCHEMA são defesas primárias contra XXE.
      // Se um provider JAXP alternativo no classpath não as suportar, o hardening
      // ficaria incompleto — logamos WARN para que o downgrade não passe despercebido.
      log.warn(
          "SECURITY: SchemaFactory nao suporta a propriedade '{}' — hardening XXE pode "
              + "estar incompleto: {}",
          name,
          e.getMessage());
    }
  }

  private String formatError(SAXParseException e) {
    return "linha " + e.getLineNumber() + ", col " + e.getColumnNumber() + ": " + e.getMessage();
  }

  // =========================================================================
  // Resolver de imports relativos do classpath
  // =========================================================================

  /**
   * Resolve os imports relativos dos XSD da NF-e a partir do classpath.
   *
   * <p>Todos os XSDs devem estar em {@code /xsd/} no classpath. Qualquer recurso
   * não reconhecido retorna {@code null} (o parser usa seu resolver padrão — mas
   * com {@code ACCESS_EXTERNAL_SCHEMA=""} isso efetivamente bloqueia externos).
   */
  private static final class ClasspathLsResourceResolver implements LSResourceResolver {

    @Override
    public LSInput resolveResource(
        String type,
        String namespaceURI,
        String publicId,
        String systemId,
        String baseURI) {

      if (systemId == null) {
        return null;
      }
      // Extrai o name do fileName do systemId (pode ter path relativo)
      String fileName = systemId.contains("/")
          ? systemId.substring(systemId.lastIndexOf('/') + 1)
          : systemId;

      InputStream stream = XsdValidator.class.getResourceAsStream("/xsd/" + fileName);
      if (stream == null) {
        log.debug("Recurso XSD nao encontrado no classpath: {}", fileName);
        return null;
      }

      return new ClasspathLsInput(publicId, systemId, stream);
    }
  }

  /** Implementação mínima de {@link LSInput} que entrega um {@link InputStream}. */
  private static final class ClasspathLsInput implements LSInput {

    private final String publicId;
    private final String systemId;
    private final InputStream stream;

    ClasspathLsInput(String publicId, String systemId, InputStream stream) {
      this.publicId = publicId;
      this.systemId = systemId;
      this.stream = stream;
    }

    @Override public InputStream getByteStream() { return stream; }
    @Override public String getPublicId() { return publicId; }
    @Override public String getSystemId() { return systemId; }
    @Override public String getBaseURI() { return null; }
    @Override public java.io.Reader getCharacterStream() { return null; }
    @Override public String getStringData() { return null; }
    @Override public String getEncoding() { return "UTF-8"; }
    @Override public boolean getCertifiedText() { return false; }

    @Override public void setPublicId(String p) {}
    @Override public void setSystemId(String s) {}
    @Override public void setBaseURI(String b) {}
    @Override public void setByteStream(InputStream i) {}
    @Override public void setCharacterStream(java.io.Reader r) {}
    @Override public void setStringData(String s) {}
    @Override public void setEncoding(String e) {}
    @Override public void setCertifiedText(boolean c) {}
  }
}
