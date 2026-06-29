package br.com.agrocontabil.nfe;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.model.RecipientAddress;
import br.com.agrocontabil.model.Invoice;
import br.com.agrocontabil.model.InvoiceItem;
import br.com.agrocontabil.model.Producer;
import br.com.agrocontabil.model.TaxRegime;
import br.com.agrocontabil.model.DocumentType;
import br.com.agrocontabil.model.ProducerType;
import br.com.agrocontabil.nfe.model.InfAdic;
import br.com.agrocontabil.nfe.model.NFe;
import br.com.agrocontabil.service.AccessKeyGenerator;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("NotaFiscalNfeMapper — diferenciacao NFA no infCpl")
class InvoiceNfeMapperNfaTest {

  private InvoiceNfeMapper mapper;

  @BeforeEach
  void setUp() {
    mapper = new InvoiceNfeMapper(new AccessKeyGenerator());
  }

  @Test
  @DisplayName("infCpl contem marcador de NOTA FISCAL AVULSA quando tipoDocumento = NFA")
  void infCplContemMarcadorNfa() {
    NFe nfe = mapper.toNfe(buildNota(DocumentType.NFA));

    InfAdic infAdic = nfe.getInfNFe().getInfAdic();
    assertThat(infAdic.getInfCpl()).contains("NOTA FISCAL AVULSA");
  }

  @Test
  @DisplayName("infCpl NAO contem marcador de NFA quando tipoDocumento = NFE")
  void infCplSemMarcadorParaNfe() {
    NFe nfe = mapper.toNfe(buildNota(DocumentType.NFE));

    InfAdic infAdic = nfe.getInfNFe().getInfAdic();
    assertThat(infAdic.getInfCpl()).doesNotContain("NOTA FISCAL AVULSA");
  }

  private Invoice buildNota(DocumentType tipo) {
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
        .description("Soja em grao")
        .ncm("12010090")
        .cfop("5101")
        .unit("KG")
        .quantity(new BigDecimal("1000.0000"))
        .unitPrice(new BigDecimal("1.0000"))
        .totalValue(new BigDecimal("1000.00"))
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
        .documentType(tipo)
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
    return invoice;
  }
}
