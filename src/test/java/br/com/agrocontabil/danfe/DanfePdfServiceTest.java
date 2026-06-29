package br.com.agrocontabil.danfe;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.model.RecipientAddress;
import br.com.agrocontabil.model.Invoice;
import br.com.agrocontabil.model.InvoiceItem;
import br.com.agrocontabil.model.Producer;
import br.com.agrocontabil.model.InvoiceStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Teste de integração do {@link DanfePdfService}.
 *
 * <p>Exercita o pipeline completo: compilação real do template {@code danfe.jrxml},
 * preenchimento com uma invoice de exemplo e exportação para PDF. Valida que a saída é um
 * documento PDF não vazio (assinatura {@code %PDF}).
 */
class DanfePdfServiceTest {

  private final DanfePdfService service = new DanfePdfService();

  @Test
  @DisplayName("gerarPdf deve produzir um PDF nao vazio com assinatura %PDF")
  void gerarPdfShouldProduceNonEmptyPdf() {
    byte[] pdf = service.generatePdf(buildNota());

    assertThat(pdf).isNotEmpty();
    // Assinatura de fileName PDF: bytes "%PDF"
    assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
  }

  @Test
  @DisplayName("gerarPdf deve funcionar mesmo com totais e tributos nulos")
  void gerarPdfShouldHandleNullTotals() {
    Invoice invoice = buildNota();
    invoice.setTotalIbsValue(null);
    invoice.setTotalCbsValue(null);

    byte[] pdf = service.generatePdf(invoice);

    assertThat(pdf).isNotEmpty();
    assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
  }

  private Invoice buildNota() {
    Producer producer = Producer.builder()
        .id(UUID.randomUUID())
        .fullName("Fazenda Boa Esperanca LTDA")
        .taxId("12345678000195")
        .state("MT")
        .build();

    InvoiceItem item = InvoiceItem.builder()
        .id(UUID.randomUUID())
        .itemNumber(1)
        .description("Soja em graos")
        .ncm("12010090")
        .cfop("6101")
        .unit("KG")
        .quantity(new BigDecimal("500.0000"))
        .unitPrice(new BigDecimal("2.0000"))
        .totalValue(new BigDecimal("1000.00"))
        .ibsValue(new BigDecimal("1.00"))
        .cbsValue(new BigDecimal("9.00"))
        .build();

    Invoice invoice = Invoice.builder()
        .id(UUID.randomUUID())
        .number(1L)
        .series(1)
        .model("55")
        .operationNature("Venda de producao rural")
        .issuanceDate(OffsetDateTime.now())
        .producer(producer)
        .recipientName("Jose Comprador")
        .recipientTaxId("98765432100")
        .recipientAddress(RecipientAddress.builder()
            .street("Rua das Flores")
            .number("100")
            .city("Cuiaba")
            .uf("MT")
            .zipCode("78000000")
            .build())
        .totalProductsValue(new BigDecimal("1000.00"))
        .totalInvoiceValue(new BigDecimal("1000.00"))
        .totalIbsValue(new BigDecimal("1.00"))
        .totalCbsValue(new BigDecimal("9.00"))
        .status(InvoiceStatus.XML_GERADO)
        .accessKey("41262612345678000195550010000000011000000012")
        .generatedXml("<NFe/>")
        .build();

    invoice.addItem(item);
    return invoice;
  }
}
