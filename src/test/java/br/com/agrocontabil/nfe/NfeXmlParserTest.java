package br.com.agrocontabil.nfe;
import br.com.agrocontabil.config.DigitalCertificateProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.agrocontabil.config.TaxProperties;
import br.com.agrocontabil.exception.XmlImportException;
import br.com.agrocontabil.model.RecipientAddress;
import br.com.agrocontabil.model.Invoice;
import br.com.agrocontabil.model.InvoiceItem;
import br.com.agrocontabil.model.Producer;
import br.com.agrocontabil.model.TaxRegime;
import br.com.agrocontabil.model.ProducerType;
import br.com.agrocontabil.nfe.model.NFe;
import br.com.agrocontabil.service.IbsCbsCalculationService;
import br.com.agrocontabil.service.AccessKeyGenerator;
import br.com.agrocontabil.service.InvoiceXmlService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("NfeXmlParser — parse XXE-safe + XSD + JAXB")
class NfeXmlParserTest {

  private NfeXmlParser parser;
  private String nfeXml; // XML real de uma NF-e (bare <NFe>)

  @BeforeEach
  void setUp() {
    parser = new NfeXmlParser(new XsdValidator());

    TaxProperties props = new TaxProperties();
    TaxProperties.Ibs ibs = new TaxProperties.Ibs();
    ibs.setAliquota(new BigDecimal("0.0010"));
    TaxProperties.Cbs cbs = new TaxProperties.Cbs();
    cbs.setAliquota(new BigDecimal("0.0090"));
    props.setIbs(ibs);
    props.setCbs(cbs);
    IbsCbsCalculationService calculo = new IbsCbsCalculationService(props);

    InvoiceXmlService pipeline = new InvoiceXmlService(
        new FiscalRulesValidator(),
        new InvoiceNfeMapper(new AccessKeyGenerator()),
        new XmlGenerator(),
        new NfeSigner(new KeyStoreCertificateProvider(
            new DigitalCertificateProperties(
                "classpath:certs/nfe-teste.p12", "changeit", "nfe-teste"))),
        new SignatureValidator(),
        new XsdValidator());

    Invoice invoice = buildNota();
    calculo.calculate(invoice);
    nfeXml = pipeline.generateXml(invoice);
  }

  @Test
  @DisplayName("parseia XML de NFe puro e retorna o grafo JAXB")
  void parseNfePuro() {
    NFe nfe = parser.parse(nfeXml.getBytes(StandardCharsets.UTF_8));
    assertThat(nfe.getInfNFe()).isNotNull();
    assertThat(nfe.getInfNFe().getId()).startsWith("NFe");
    assertThat(nfe.getInfNFe().getEmit()).isNotNull();
  }

  @Test
  @DisplayName("parseia XML de NFe dentro de nfeProc")
  void parseNfeDentroDeNfeProc() {
    String semProlog = nfeXml.replaceFirst("<\\?xml[^>]*\\?>", "");
    String nfeProc =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<nfeProc versao=\"4.00\" xmlns=\"http://www.portalfiscal.inf.br/nfe\">"
            + semProlog
            + "</nfeProc>";

    NFe nfe = parser.parse(nfeProc.getBytes(StandardCharsets.UTF_8));
    assertThat(nfe.getInfNFe()).isNotNull();
    assertThat(nfe.getInfNFe().getId()).startsWith("NFe");
  }

  @Test
  @DisplayName("XML malformado lanca XmlImportacaoException")
  void xmlMalformado() {
    assertThatThrownBy(() -> parser.parse("<isto nao e xml valido".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(XmlImportException.class);
  }

  @Test
  @DisplayName("XML sem elemento NFe lanca XmlImportacaoException")
  void xmlSemNfe() {
    String semNfe = "<?xml version=\"1.0\"?><outraCoisa></outraCoisa>";
    assertThatThrownBy(() -> parser.parse(semNfe.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(XmlImportException.class);
  }

  @Test
  @DisplayName("XML com DOCTYPE/entidade externa (XXE) e rejeitado com seguranca")
  void xxeRejeitado() {
    String xxe =
        "<?xml version=\"1.0\"?>"
            + "<!DOCTYPE foo [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
            + "<NFe xmlns=\"http://www.portalfiscal.inf.br/nfe\"><infNFe>&xxe;</infNFe></NFe>";
    assertThatThrownBy(() -> parser.parse(xxe.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(XmlImportException.class);
  }

  private Invoice buildNota() {
    Producer producer = Producer.builder()
        .id(UUID.randomUUID())
        .fullName("EMITENTE TERCEIRO LTDA")
        .taxId("12345678000195")
        .producerType(ProducerType.PESSOA_JURIDICA)
        .taxRegime(TaxRegime.SIMPLES)
        .state("PR")
        .build();

    InvoiceItem item = InvoiceItem.builder()
        .id(UUID.randomUUID())
        .itemNumber(1)
        .productCode("SOJA001")
        .description("Soja em grao")
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
        .street("Rua das Flores").number("100").city("Curitiba")
        .ibgeCityCode("4106902").uf("PR").zipCode("80010000").build();

    Invoice invoice = Invoice.builder()
        .id(UUID.randomUUID())
        .number(1L).series(1).model("55")
        .operationNature("Venda de producao rural")
        .issuanceDate(OffsetDateTime.of(2026, 12, 1, 10, 0, 0, 0, ZoneOffset.of("-03:00")))
        .producer(producer)
        .recipientName("COMPRADORA SA")
        .recipientTaxId("98765432000199")
        .recipientAddress(endDest)
        .totalProductsValue(new BigDecimal("1000.00"))
        .totalInvoiceValue(new BigDecimal("1000.00"))
        .build();
    invoice.addItem(item);
    return invoice;
  }
}
