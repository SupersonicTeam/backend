package br.com.agrocontabil.service;

import br.com.agrocontabil.common.validation.CpfUtils;
import br.com.agrocontabil.exception.NfaInvalidIssuerException;
import br.com.agrocontabil.model.Invoice;
import br.com.agrocontabil.model.Producer;
import br.com.agrocontabil.model.ProducerType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orquestra a geracao da Nota Fiscal Avulsa (NFA-e) do producer rural pessoa fisica.
 *
 * <p>A NFA-e reusa o layout NF-e 4.00 model 55: este servico nao duplica o pipeline
 * de marshalling/validacao XSD — ele <strong>compoe</strong> o
 * {@link InvoiceXmlService}, adicionando apenas as pre-condicoes especificas
 * da NFA antes de delegar:
 * <ol>
 *   <li>O emitente (producer) deve ser {@link ProducerType#PESSOA_FISICA}.</li>
 *   <li>O CPF do emitente deve ser estruturalmente valido (modulo 11).</li>
 * </ol>
 *
 * <p>Qualquer violacao lanca {@link NfaInvalidIssuerException} (HTTP 422). O
 * {@link br.com.agrocontabil.nfe.InvoiceNfeMapper} ja emite {@code <CPF>} para
 * emitente PF, portanto o XML resultante esta estruturalmente correto para a NFA.
 */
@Service
public class NfaXmlService {

  private static final Logger log = LoggerFactory.getLogger(NfaXmlService.class);

  private final InvoiceXmlService invoiceXmlService;

  public NfaXmlService(InvoiceXmlService invoiceXmlService) {
    this.invoiceXmlService = invoiceXmlService;
  }

  /**
   * Valida o emitente PF + CPF e gera o XML da NFA delegando ao pipeline NF-e.
   *
   * @param invoice invoice fiscal com {@code documentType = NFA} e producer PF associado
   * @return XML da NFA-e (layout 4.00, model 55), validado contra o XSD
   * @throws NfaInvalidIssuerException se o producer nao for PF ou o CPF for invalido
   */
  public String generateXml(Invoice invoice) {
    Producer producer = invoice.getProducer();

    if (producer == null || producer.getProducerType() != ProducerType.PESSOA_FISICA) {
      log.warn("Geracao de NFA rejeitada: emitente nao e pessoa fisica. notaId={}",
          invoice.getId());
      throw new NfaInvalidIssuerException(
          "NFA exige emitente pessoa fisica (produtor rural PF).");
    }

    if (!CpfUtils.isValid(producer.getTaxId())) {
      log.warn("Geracao de NFA rejeitada: CPF do emitente invalido. notaId={}",
          invoice.getId());
      throw new NfaInvalidIssuerException(
          "CPF do produtor emitente da NFA e invalido.");
    }

    log.info("Gerando XML da NFA: notaId={} produtor={}", invoice.getId(), producer.getId());
    return invoiceXmlService.generateXml(invoice);
  }
}
