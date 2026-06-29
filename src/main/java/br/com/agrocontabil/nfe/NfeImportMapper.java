package br.com.agrocontabil.nfe;

import br.com.agrocontabil.exception.XmlImportException;
import br.com.agrocontabil.model.RecipientAddress;
import br.com.agrocontabil.model.Invoice;
import br.com.agrocontabil.model.InvoiceItem;
import br.com.agrocontabil.model.InvoiceOrigin;
import br.com.agrocontabil.model.Producer;
import br.com.agrocontabil.model.InvoiceStatus;
import br.com.agrocontabil.model.DocumentType;
import br.com.agrocontabil.nfe.model.Dest;
import br.com.agrocontabil.nfe.model.Det;
import br.com.agrocontabil.nfe.model.Emit;
import br.com.agrocontabil.nfe.model.EnderDest;
import br.com.agrocontabil.nfe.model.Ide;
import br.com.agrocontabil.nfe.model.InfNFe;
import br.com.agrocontabil.nfe.model.NFe;
import br.com.agrocontabil.nfe.model.Prod;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Component;

/**
 * Mapeia o grafo JAXB {@link NFe} (XML importado de terceiro) para a entidade
 * {@link Invoice}, marcando-a como {@link InvoiceOrigin#IMPORTADA} e
 * {@link InvoiceStatus#AUTORIZADA}.
 *
 * <p>O emitente do XML (terceiro) vai para {@code issuerName}/{@code issuerTaxId};
 * o {@code <dest>} do XML preenche o bloco destinatário; o {@code producer} é o owner
 * autenticado (passado por parâmetro). Campos IBS/CBS por item ficam fora deste mapper
 * (o XML completo é preservado em {@code generatedXml}).
 */
@Component
public class NfeImportMapper {

  /**
   * Constrói uma {@link Invoice} importada a partir do grafo JAXB.
   *
   * @param nfe         grafo da NF-e importada
   * @param xmlOriginal XML original (armazenado em {@code generatedXml})
   * @param owner        producer autenticado, owner da invoice importada
   * @return entidade pronta para persistência
   * @throws XmlImportException se campos obrigatórios estiverem ausentes/inválidos
   */
  public Invoice toEntity(NFe nfe, String xmlOriginal, Producer owner) {
    InfNFe inf = nfe.getInfNFe();
    if (inf == null || inf.getIde() == null || inf.getEmit() == null) {
      throw new XmlImportException("XML da NF-e sem grupos obrigatorios (ide/emit).");
    }
    Ide ide = inf.getIde();
    Emit emit = inf.getEmit();

    Invoice invoice = Invoice.builder()
        .accessKey(extractKey(inf.getId()))
        .number(parseLong(ide.getNNF(), "ide.nNF"))
        .series(parseInt(ide.getSerie(), "ide.serie"))
        .model(ide.getMod() != null ? ide.getMod() : "55")
        .documentType(DocumentType.NFE)
        .origin(InvoiceOrigin.IMPORTADA)
        .status(InvoiceStatus.AUTORIZADA)
        .operationNature(ide.getNatOp() != null ? ide.getNatOp() : "IMPORTADA")
        .issuanceDate(parseDate(ide.getDhEmi()))
        .producer(owner)
        .issuerName(emit.getXNome())
        .issuerTaxId(emit.getCnpj() != null ? emit.getCnpj() : emit.getCpf())
        .nfeLayoutVersion(inf.getVersao())
        .generatedXml(xmlOriginal)
        .xmlGenerationDate(OffsetDateTime.now())
        .totalProductsValue(extractVProd(inf))
        .totalInvoiceValue(extractVnf(inf))
        .build();

    applyRecipient(invoice, inf.getDest());
    applyItems(invoice, inf);
    return invoice;
  }

  private void applyRecipient(Invoice invoice, Dest dest) {
    if (dest == null) {
      invoice.setRecipientName("DESTINATARIO NAO INFORMADO");
      invoice.setRecipientTaxId("00000000000");
      return;
    }
    invoice.setRecipientName(dest.getXNome() != null ? dest.getXNome() : "DESTINATARIO");
    invoice.setRecipientTaxId(dest.getCnpj() != null ? dest.getCnpj() : dest.getCpf());
    invoice.setRecipientStateRegistration(dest.getIe());
    EnderDest end = dest.getEnderDest();
    if (end != null) {
      invoice.setRecipientAddress(RecipientAddress.builder()
          .street(end.getXLgr())
          .number(end.getNro())
          .city(end.getXMun())
          .ibgeCityCode(end.getCMun())
          .uf(end.getUf())
          .zipCode(end.getCep())
          .build());
    }
  }

  private void applyItems(Invoice invoice, InfNFe inf) {
    if (inf.getDet() == null) {
      return;
    }
    for (Det det : inf.getDet()) {
      Prod prod = det.getProd();
      if (prod == null) {
        continue;
      }
      InvoiceItem item = InvoiceItem.builder()
          .itemNumber(parseInt(det.getNItem(), "det.nItem"))
          .productCode(prod.getCProd())
          .description(prod.getXProd())
          .ncm(prod.getNcm())
          .cfop(prod.getCfop())
          .unit(prod.getUCom())
          .quantity(parseDecimal(prod.getQCom(), "prod.qCom"))
          .unitPrice(parseDecimal(prod.getVUnCom(), "prod.vUnCom"))
          .totalValue(parseDecimal(prod.getVProd(), "prod.vProd"))
          .build();
      invoice.addItem(item);
    }
  }

  private String extractKey(String id) {
    if (id == null || !id.startsWith("NFe") || id.length() != 47) {
      throw new XmlImportException("Atributo Id da infNFe ausente ou invalido: " + id);
    }
    return id.substring(3);
  }

  private BigDecimal extractVProd(InfNFe inf) {
    if (inf.getTotal() != null && inf.getTotal().getIcmsTot() != null
        && inf.getTotal().getIcmsTot().getVProd() != null) {
      return new BigDecimal(inf.getTotal().getIcmsTot().getVProd());
    }
    return BigDecimal.ZERO;
  }

  private BigDecimal extractVnf(InfNFe inf) {
    if (inf.getTotal() != null && inf.getTotal().getIcmsTot() != null
        && inf.getTotal().getIcmsTot().getVNF() != null) {
      return new BigDecimal(inf.getTotal().getIcmsTot().getVNF());
    }
    return BigDecimal.ZERO;
  }

  private long parseLong(String value, String field) {
    try {
      return Long.parseLong(value.trim());
    } catch (Exception e) {
      throw new XmlImportException("Campo numerico invalido (" + field + "): " + value);
    }
  }

  private int parseInt(String value, String field) {
    try {
      return Integer.parseInt(value.trim());
    } catch (Exception e) {
      throw new XmlImportException("Campo inteiro invalido (" + field + "): " + value);
    }
  }

  private BigDecimal parseDecimal(String value, String field) {
    try {
      return new BigDecimal(value.trim());
    } catch (Exception e) {
      throw new XmlImportException("Campo decimal invalido (" + field + "): " + value);
    }
  }

  private OffsetDateTime parseDate(String dhEmi) {
    try {
      return OffsetDateTime.parse(dhEmi.trim());
    } catch (Exception e) {
      throw new XmlImportException("Data de emissao (dhEmi) invalida: " + dhEmi);
    }
  }
}
