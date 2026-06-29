package br.com.agrocontabil.nfe;
import br.com.agrocontabil.config.DigitalCertificateProperties;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.config.TaxProperties;
import br.com.agrocontabil.model.RecipientAddress;
import br.com.agrocontabil.model.Invoice;
import br.com.agrocontabil.model.InvoiceItem;
import br.com.agrocontabil.model.InvoiceOrigin;
import br.com.agrocontabil.model.Producer;
import br.com.agrocontabil.model.TaxRegime;
import br.com.agrocontabil.model.InvoiceStatus;
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

@DisplayName("NfeImportMapper — JAXB NFe -> NotaFiscal")
class NfeImportMapperTest {

  private NfeImportMapper mapper;
  private NfeXmlParser parser;
  private String nfeXml;

  @BeforeEach
  void setUp() {
    mapper = new NfeImportMapper();
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

    Invoice origin = buildNota();
    calculo.calculate(origin);
    nfeXml = pipeline.generateXml(origin);
  }

  @Test
  @DisplayName("extrai campos principais e marca como IMPORTADA/AUTORIZADA")
  void extraiCampos() {
    NFe nfe = parser.parse(nfeXml.getBytes(StandardCharsets.UTF_8));
    Producer owner = Producer.builder().id(UUID.randomUUID()).fullName("Dono").build();

    Invoice invoice = mapper.toEntity(nfe, nfeXml, owner);

    assertThat(invoice.getOrigin()).isEqualTo(InvoiceOrigin.IMPORTADA);
    assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.AUTORIZADA);
    assertThat(invoice.getProducer()).isSameAs(owner);
    assertThat(invoice.getAccessKey()).hasSize(44);
    assertThat(invoice.getModel()).isEqualTo("55");
    assertThat(invoice.getNumber()).isEqualTo(1L);
    assertThat(invoice.getIssuerTaxId()).isEqualTo("12345678000195");
    assertThat(invoice.getIssuerName()).isNotBlank();
    assertThat(invoice.getRecipientTaxId()).isEqualTo("98765432000199");
    assertThat(invoice.getItems()).hasSize(1);
    assertThat(invoice.getItems().get(0).getNcm()).isEqualTo("12010090");
    assertThat(invoice.getItems().get(0).getCfop()).isEqualTo("5101");
    assertThat(invoice.getTotalProductsValue()).isEqualByComparingTo("1000.00");
    assertThat(invoice.getGeneratedXml()).isEqualTo(nfeXml);
    assertThat(invoice.getXmlGenerationDate()).isNotNull();
  }

  private Invoice buildNota() {
    Producer producer = Producer.builder()
        .id(UUID.randomUUID()).fullName("EMITENTE TERCEIRO LTDA")
        .taxId("12345678000195").producerType(ProducerType.PESSOA_JURIDICA)
        .taxRegime(TaxRegime.SIMPLES).state("PR").build();
    InvoiceItem item = InvoiceItem.builder()
        .id(UUID.randomUUID()).itemNumber(1).productCode("SOJA001").description("Soja em grao")
        .ncm("12010090").cfop("5101").unit("KG")
        .quantity(new BigDecimal("1000.0000")).unitPrice(new BigDecimal("1.0000"))
        .totalValue(new BigDecimal("1000.00")).cstIbsCbs("101").cClassTrib("010100").build();
    RecipientAddress endDest = RecipientAddress.builder()
        .street("Rua das Flores").number("100").city("Curitiba")
        .ibgeCityCode("4106902").uf("PR").zipCode("80010000").build();
    Invoice invoice = Invoice.builder()
        .id(UUID.randomUUID()).number(1L).series(1).model("55")
        .operationNature("Venda de producao rural")
        .issuanceDate(OffsetDateTime.of(2026, 12, 1, 10, 0, 0, 0, ZoneOffset.of("-03:00")))
        .producer(producer).recipientName("COMPRADORA SA")
        .recipientTaxId("98765432000199").recipientAddress(endDest)
        .totalProductsValue(new BigDecimal("1000.00")).totalInvoiceValue(new BigDecimal("1000.00"))
        .build();
    invoice.addItem(item);
    return invoice;
  }
}
