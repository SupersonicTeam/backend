package br.com.agrocontabil.service;
import br.com.agrocontabil.config.DigitalCertificateProperties;
import br.com.agrocontabil.nfe.SignatureValidator;
import br.com.agrocontabil.nfe.KeyStoreCertificateProvider;
import br.com.agrocontabil.nfe.NfeSigner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.agrocontabil.config.TaxProperties;
import br.com.agrocontabil.exception.NfaInvalidIssuerException;
import br.com.agrocontabil.model.RecipientAddress;
import br.com.agrocontabil.model.Invoice;
import br.com.agrocontabil.model.InvoiceItem;
import br.com.agrocontabil.model.Producer;
import br.com.agrocontabil.model.TaxRegime;
import br.com.agrocontabil.model.DocumentType;
import br.com.agrocontabil.model.ProducerType;
import br.com.agrocontabil.nfe.FiscalRulesValidator;
import br.com.agrocontabil.nfe.InvoiceNfeMapper;
import br.com.agrocontabil.nfe.XmlGenerator;
import br.com.agrocontabil.nfe.XsdValidator;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("GeracaoNfaService — pipeline real (sem Spring, sem banco)")
class NfaXmlServicePipelineTest {

  private NfaXmlService nfaService;
  private IbsCbsCalculationService ibsCbsCalculationService;

  @BeforeEach
  void setUp() {
    TaxProperties props = new TaxProperties();
    TaxProperties.Ibs ibs = new TaxProperties.Ibs();
    ibs.setAliquota(new BigDecimal("0.0010"));
    TaxProperties.Cbs cbs = new TaxProperties.Cbs();
    cbs.setAliquota(new BigDecimal("0.0090"));
    props.setIbs(ibs);
    props.setCbs(cbs);
    ibsCbsCalculationService = new IbsCbsCalculationService(props);

    InvoiceXmlService pipeline = new InvoiceXmlService(
        new FiscalRulesValidator(),
        new InvoiceNfeMapper(new AccessKeyGenerator()),
        new XmlGenerator(),
        new NfeSigner(new KeyStoreCertificateProvider(
            new DigitalCertificateProperties(
                "classpath:certs/nfe-teste.p12", "changeit", "nfe-teste"))),
        new SignatureValidator(),
        new XsdValidator());
    nfaService = new NfaXmlService(pipeline);
  }

  @Test
  @DisplayName("gera XML valido com CPF do emitente e marcador NFA para produtor PF")
  void geraXmlValidoParaNfaPf() {
    Invoice invoice = buildNfaCompleta();
    ibsCbsCalculationService.calculate(invoice);

    String xml = nfaService.generateXml(invoice);

    assertThat(xml).startsWith("<?xml");
    assertThat(xml).contains("<CPF>52998224725</CPF>");
    assertThat(xml).contains("NOTA FISCAL AVULSA");
  }

  @Test
  @DisplayName("rejeita (422) NFA cujo emitente e PJ, sem gerar XML")
  void rejeitaNfaComEmitentePj() {
    Invoice invoice = buildNfaCompleta();
    invoice.getProducer().setProducerType(ProducerType.PESSOA_JURIDICA);
    invoice.getProducer().setTaxId("12345678000195");
    ibsCbsCalculationService.calculate(invoice);

    assertThatThrownBy(() -> nfaService.generateXml(invoice))
        .isInstanceOf(NfaInvalidIssuerException.class);
  }

  private Invoice buildNfaCompleta() {
    Producer producer = Producer.builder()
        .id(UUID.randomUUID())
        .fullName("JOAO DA SILVA AGRICULTOR")
        .taxId("52998224725")
        .producerType(ProducerType.PESSOA_FISICA)
        .taxRegime(TaxRegime.SIMPLES)
        .state("PR")
        .build();

    InvoiceItem item = InvoiceItem.builder()
        .id(UUID.randomUUID())
        .itemNumber(1)
        .productCode("SOJA001")
        .description("Soja em grao safra 2026")
        .ncm("12010090")
        .cfop("5101")
        .unit("KG")
        .quantity(new BigDecimal("1000.0000"))
        .unitPrice(new BigDecimal("1.0000"))
        .totalValue(new BigDecimal("1000.00"))
        .cstIbsCbs("101")
        .cClassTrib("010100")
        .build();

    RecipientAddress endDest = RecipientAddress.builder()
        .street("Rua das Flores")
        .number("100")
        .city("Curitiba")
        .ibgeCityCode("4106902")
        .uf("PR")
        .zipCode("80010000")
        .build();

    Invoice invoice = Invoice.builder()
        .id(UUID.randomUUID())
        .number(1L)
        .series(1)
        .model("55")
        .documentType(DocumentType.NFA)
        .operationNature("Venda de producao rural")
        .issuanceDate(OffsetDateTime.of(2026, 12, 1, 10, 0, 0, 0, ZoneOffset.of("-03:00")))
        .producer(producer)
        .recipientName("COMPRADORA SA")
        .recipientTaxId("12345678000195")
        .recipientAddress(endDest)
        .totalProductsValue(new BigDecimal("1000.00"))
        .totalInvoiceValue(new BigDecimal("1000.00"))
        .build();

    invoice.addItem(item);
    item.setInvoice(invoice);

    return invoice;
  }
}
