package br.com.agrocontabil.nfe;

import br.com.agrocontabil.exception.XmlImportacaoInvalidaException;
import br.com.agrocontabil.nfe.model.NFe;
import br.com.agrocontabil.nfe.model.NfeNamespace;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Unmarshaller;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Le e valida um XML de NF-e submetido para importacao (issue #19 — UC002), produzindo o
 * grafo JAXB {@link NFe}.
 *
 * <p>Aceita tanto o envelope {@code <nfeProc>} (NF-e processada, com {@code <protNFe>})
 * quanto um {@code <NFe>} puro. Em ambos os casos, o elemento {@code <NFe>} e localizado de
 * forma <em>namespace-aware</em> (namespace oficial {@value NfeNamespace#NS}).
 *
 * <h3>Seguranca (prevencao de XXE):</h3>
 * <ul>
 *   <li>O parsing DOM usa {@link SecureXmlFactories#hardenedDocumentBuilderFactory()}
 *       (DOCTYPE proibido, entidades externas desabilitadas, sem carga de DTD externo).</li>
 *   <li>O unmarshalling JAXB nao recebe a string bruta: recebe um {@link XMLStreamReader}
 *       de um {@link XMLInputFactory} endurecido
 *       ({@link SecureXmlFactories#hardenedXmlInputFactory()}), sem suporte a DTD nem a
 *       entidades externas.</li>
 *   <li>Nenhuma resolucao de recurso externo (arquivo/rede) e possivel a partir do
 *       documento importado — um XML com entidade externa maliciosa e rejeitado, sem vazar
 *       o conteudo do alvo.</li>
 * </ul>
 *
 * <h3>Validacao estrutural:</h3>
 * <p>O elemento {@code <NFe>} extraido (preservando a assinatura {@code ds:Signature} quando
 * presente) e validado contra o XSD oficial do layout 4.00 via {@link XsdValidator}. Como o
 * {@link XsdValidator} sinaliza falha com semantica de <em>emissao</em>
 * ({@link br.com.agrocontabil.exception.XmlGenerationException}), aqui a falha e traduzida
 * para {@link XmlImportacaoInvalidaException} (semantica de <em>importacao</em>).
 */
@Component
public class NfeXmlImporter {

  private static final Logger log = LoggerFactory.getLogger(NfeXmlImporter.class);

  private static final String CSTAT_AUTORIZADA = "100";

  private final XsdValidator xsdValidator;
  private final AtomicReference<JAXBContext> contextCache = new AtomicReference<>();

  /** Constroi o importer injetando o validador XSD reaproveitado da emissao. */
  public NfeXmlImporter(XsdValidator xsdValidator) {
    this.xsdValidator = xsdValidator;
  }

  /**
   * Resultado da importacao de um XML: o grafo desserializado e o indicador de autorizacao.
   *
   * @param nfe        grafo JAXB desserializado do elemento {@code <NFe>}
   * @param nfeXml     XML bruto do elemento {@code <NFe>} (assinado, para auditoria/persistencia)
   * @param autorizada {@code true} se o XML e um {@code nfeProc} autorizado (protNFe cStat=100)
   */
  public record ResultadoImportacao(NFe nfe, String nfeXml, boolean autorizada) {}

  /**
   * Le, valida e converte um XML de NF-e para o grafo JAXB {@link NFe}.
   *
   * @param xmlBytes conteudo bruto do arquivo XML enviado
   * @return {@link ResultadoImportacao} com o grafo, o XML da NFe e o indicador de autorizacao
   * @throws XmlImportacaoInvalidaException se o XML for malformado, nao contiver um
   *     elemento {@code <NFe>} reconhecivel ou reprovar na validacao XSD (HTTP 422)
   */
  public ResultadoImportacao importar(byte[] xmlBytes) {
    if (xmlBytes == null || xmlBytes.length == 0) {
      throw new XmlImportacaoInvalidaException("Arquivo XML vazio.");
    }

    // 1) Parse DOM endurecido (XXE-safe) e localizacao namespace-aware do elemento <NFe>.
    Document document = parseSeguro(xmlBytes);
    Node nfeNode = localizarNfe(document);
    if (nfeNode == null) {
      throw new XmlImportacaoInvalidaException(
          "Elemento <NFe> nao encontrado no XML. Envie uma NF-e (nfeProc ou NFe) valida.");
    }

    // 2) Serializa o subtree <NFe> (preservando ds:Signature) e valida contra o XSD 4.00.
    String nfeXml = serializar(nfeNode);
    validarXsd(nfeXml);

    // 3) Unmarshalling JAXB via StAX endurecido (defesa em profundidade contra XXE).
    NFe nfe = unmarshal(nfeXml);

    boolean autorizada = isNfeProcAutorizada(document);
    return new ResultadoImportacao(nfe, nfeXml, autorizada);
  }

  /**
   * Detecta se o documento e um {@code nfeProc} com protocolo autorizado (cStat=100).
   *
   * <p>Busca de forma namespace-aware o elemento {@code cStat} dentro de {@code protNFe/infProt}.
   * Um {@code <NFe>} puro (sem protocolo) retorna {@code false} (status neutro).
   */
  private boolean isNfeProcAutorizada(Document document) {
    NodeList cStatNodes = document.getElementsByTagNameNS(NfeNamespace.NS, "cStat");
    for (int i = 0; i < cStatNodes.getLength(); i++) {
      Node parent = cStatNodes.item(i).getParentNode();
      // Considera apenas o cStat do protocolo (infProt), nao de outros grupos.
      if (parent != null && "infProt".equals(parent.getLocalName())) {
        return CSTAT_AUTORIZADA.equals(cStatNodes.item(i).getTextContent().trim());
      }
    }
    return false;
  }

  // =========================================================================
  // Etapas internas
  // =========================================================================

  private Document parseSeguro(byte[] xmlBytes) {
    try {
      return SecureXmlFactories.hardenedDocumentBuilderFactory()
          .newDocumentBuilder()
          .parse(new ByteArrayInputStream(xmlBytes));
    } catch (Exception e) {
      // Inclui SAXParseException por DOCTYPE proibido (vetor XXE) e XML malformado.
      log.warn("Falha ao parsear XML importado (rejeitado por seguranca ou malformacao): {}",
          e.getMessage());
      throw new XmlImportacaoInvalidaException(
          "XML malformado ou nao permitido (DOCTYPE/entidades externas sao bloqueados).");
    }
  }

  private Node localizarNfe(Document document) {
    // Namespace-aware: cobre tanto <NFe> puro (raiz) quanto <nfeProc><NFe>...</NFe></nfeProc>.
    Node root = document.getDocumentElement();
    if (root != null && "NFe".equals(root.getLocalName())
        && NfeNamespace.NS.equals(root.getNamespaceURI())) {
      return root;
    }
    NodeList nodes = document.getElementsByTagNameNS(NfeNamespace.NS, "NFe");
    return nodes.getLength() > 0 ? nodes.item(0) : null;
  }

  private String serializar(Node nfeNode) {
    try {
      Transformer transformer = SecureXmlFactories.hardenedTransformerFactory().newTransformer();
      transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
      transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
      java.io.StringWriter writer = new java.io.StringWriter();
      transformer.transform(new DOMSource(nfeNode), new StreamResult(writer));
      return writer.toString();
    } catch (TransformerException e) {
      log.warn("Falha ao serializar o elemento <NFe> do XML importado: {}", e.getMessage());
      throw new XmlImportacaoInvalidaException("Nao foi possivel processar o elemento <NFe>.");
    }
  }

  private void validarXsd(String nfeXml) {
    try {
      xsdValidator.validar(nfeXml);
    } catch (RuntimeException e) {
      // XsdValidator lanca XmlGenerationException (semantica de emissao). Traduzimos para a
      // semantica de importacao sem vazar detalhe tecnico sensivel ao cliente.
      log.warn("XML importado reprovado na validacao XSD: {}", e.getMessage());
      throw new XmlImportacaoInvalidaException(
          "XML nao esta em conformidade com o layout oficial da NF-e (XSD 4.00).");
    }
  }

  private NFe unmarshal(String nfeXml) {
    XMLStreamReader reader = null;
    try {
      XMLInputFactory xif = SecureXmlFactories.hardenedXmlInputFactory();
      reader = xif.createXMLStreamReader(
          new ByteArrayInputStream(nfeXml.getBytes(StandardCharsets.UTF_8)),
          StandardCharsets.UTF_8.name());
      avancarAteElemento(reader);

      Unmarshaller unmarshaller = getContext().createUnmarshaller();
      return unmarshaller.unmarshal(reader, NFe.class).getValue();
    } catch (JAXBException | XMLStreamException e) {
      log.warn("Falha ao desserializar (unmarshal) o XML importado: {}", e.getMessage());
      throw new XmlImportacaoInvalidaException(
          "Nao foi possivel interpretar a estrutura da NF-e no XML enviado.");
    } finally {
      if (reader != null) {
        try {
          reader.close();
        } catch (XMLStreamException ignored) {
          // fechamento best-effort
        }
      }
    }
  }

  private void avancarAteElemento(XMLStreamReader reader) throws XMLStreamException {
    while (reader.hasNext() && reader.getEventType() != XMLStreamConstants.START_ELEMENT) {
      reader.next();
    }
  }

  private JAXBContext getContext() throws JAXBException {
    JAXBContext ctx = contextCache.get();
    if (ctx != null) {
      return ctx;
    }
    JAXBContext newCtx = JAXBContext.newInstance(NFe.class);
    contextCache.compareAndSet(null, newCtx);
    return contextCache.get();
  }
}
