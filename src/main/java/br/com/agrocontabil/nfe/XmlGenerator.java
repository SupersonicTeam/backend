package br.com.agrocontabil.nfe;

import br.com.agrocontabil.exception.XmlGenerationException;
import br.com.agrocontabil.nfe.model.IbsCbs;
import br.com.agrocontabil.nfe.model.IbsCbsTot;
import br.com.agrocontabil.nfe.model.NFe;
import br.com.agrocontabil.nfe.model.NfeNamespace;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Marshaller;
import java.io.StringWriter;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * Serializa o grafo de objetos JAXB {@link NFe} para uma {@code String} XML UTF-8.
 *
 * <p>O marshalling segue as exigências da SEFAZ:
 * <ul>
 *   <li>Sem indentação ({@code JAXB_FORMATTED_OUTPUT = false})</li>
 *   <li>Declaração XML presente ({@code JAXB_FRAGMENT = false})</li>
 *   <li>Encoding UTF-8</li>
 * </ul>
 *
 * <p>O {@link JAXBContext} é criado uma única vez e reutilizado (cache via
 * {@link AtomicReference} para segurança em ambiente multithread). As classes
 * de tipos internos ({@code static nested}) do {@link IbsCbs} e {@link IbsCbsTot}
 * precisam ser registradas explicitamente no contexto.
 */
@Component
public class XmlGenerator {

  private final AtomicReference<JAXBContext> contextCache = new AtomicReference<>();

  /**
   * Serializa um grafo {@link NFe} para XML UTF-8.
   *
   * @param nfe grafo JAXB populado pelo mapper
   * @return string XML completa (inclui declaração {@code <?xml ...?>})
   * @throws XmlGenerationException se ocorrer falha de marshalling JAXB
   */
  public String marshal(NFe nfe) {
    try {
      Marshaller marshaller = getContext().createMarshaller();
      marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.FALSE);
      marshaller.setProperty(Marshaller.JAXB_ENCODING, "UTF-8");
      // JAXB_FRAGMENT = false garante a declaração <?xml version="1.0" encoding="UTF-8"?>
      marshaller.setProperty(Marshaller.JAXB_FRAGMENT, Boolean.FALSE);

      StringWriter writer = new StringWriter();
      marshaller.marshal(nfe, writer);
      return writer.toString();
    } catch (JAXBException e) {
      throw new XmlGenerationException("Falha ao serializar NF-e para XML: " + e.getMessage(), e);
    }
  }

  /**
   * Retorna o {@link JAXBContext} cacheado, criando-o na primeira chamada.
   *
   * <p>Inclui todas as classes do modelo NF-e (pacote principal e tipos internos
   * dos grupos IBS/CBS) necessárias para o marshalling correto.
   */
  private JAXBContext getContext() throws JAXBException {
    JAXBContext ctx = contextCache.get();
    if (ctx != null) {
      return ctx;
    }
    // Registra todas as classes necessárias, incluindo nested static types
    JAXBContext newCtx = JAXBContext.newInstance(
        NFe.class,
        IbsCbs.class,
        IbsCbs.GIbsCbs.class,
        IbsCbs.GIbsUf.class,
        IbsCbs.GIbsMun.class,
        IbsCbs.GCbs.class,
        IbsCbsTot.class,
        IbsCbsTot.GIbsTot.class,
        IbsCbsTot.GIbsUfTot.class,
        IbsCbsTot.GIbsMunTot.class,
        IbsCbsTot.GCbsTot.class);
    // CAS para evitar race condition: se outro thread criou antes, usa o existente
    contextCache.compareAndSet(null, newCtx);
    return contextCache.get();
  }

  /**
   * Retorna o namespace da NF-e para referência externa.
   *
   * @return namespace {@value NfeNamespace#NS}
   */
  public String getNamespace() {
    return NfeNamespace.NS;
  }
}
