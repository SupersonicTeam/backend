package br.com.agrocontabil.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.dto.InvoiceItemRequest;
import br.com.agrocontabil.dto.InvoiceItemResponse;
import br.com.agrocontabil.dto.InvoiceRequest;
import br.com.agrocontabil.dto.InvoiceResponse;
import br.com.agrocontabil.model.RecipientAddress;
import br.com.agrocontabil.model.Invoice;
import br.com.agrocontabil.model.InvoiceItem;
import br.com.agrocontabil.model.Producer;
import br.com.agrocontabil.model.InvoiceStatus;
import br.com.agrocontabil.model.DocumentType;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Testes unitarios do {@link InvoiceMapper}.
 *
 * <p>Nao usa contexto Spring — mapper e uma classe de metodos estaticos.
 * Cobre a logica de calculo dos totais, arredondamento HALF_UP, model fixo "55",
 * status RASCUNHO e conversao de enderecos nulos/nao-nulos.
 *
 * <p>Cenarios cobertos:
 * <ul>
 *   <li>toEntity: campos mapeados, model "55", status RASCUNHO, series padrao</li>
 *   <li>toEntity: totalValue por item com arredondamento HALF_UP em 2dp</li>
 *   <li>toEntity: totalProductsValue = sum dos totalValue dos items</li>
 *   <li>toEntity: totalInvoiceValue == totalProductsValue (Sprint 2, sem impostos)</li>
 *   <li>toEntity: endereco nulo (recipientAddress null) mapeado como null</li>
 *   <li>toEntity: endereco preenchido mapeado corretamente</li>
 *   <li>toEntity: producer autenticado vinculado corretamente</li>
 *   <li>toResponse: todos os campos de Invoice mapeados para InvoiceResponse</li>
 *   <li>toResponse: items mapeados com totalValue correto</li>
 *   <li>toResponse: endereco nulo permanece nulo na response</li>
 *   <li>toItemEntityList: converte lista de requests em entidades sem associacao com invoice</li>
 * </ul>
 */
class InvoiceMapperTest {

  private Producer producer;
  private UUID producerId;

  @BeforeEach
  void setUp() {
    producerId = UUID.randomUUID();
    producer = Producer.builder()
        .id(producerId)
        .fullName("Murilo Produtor")
        .taxId("12345678901")
        .build();
  }

  // -------------------------------------------------------------------------
  // toEntity — campos basicos, model, status, series
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("toEntity deve fixar modelo '55' independente do request")
  void toEntityShouldFixModeloAs55() {
    InvoiceRequest request = buildRequest(List.of(buildItem("10", "1.00")));

    Invoice invoice = InvoiceMapper.toEntity(request, producer);

    assertThat(invoice.getModel()).isEqualTo("55");
  }

  @Test
  @DisplayName("toEntity deve definir status inicial como RASCUNHO")
  void toEntityShouldSetStatusAsRascunho() {
    InvoiceRequest request = buildRequest(List.of(buildItem("1", "5.00")));

    Invoice invoice = InvoiceMapper.toEntity(request, producer);

    assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.RASCUNHO);
  }

  @Test
  @DisplayName("toEntity deve usar serie 1 como padrao quando serie e null no request")
  void toEntityShouldDefaultSerieToOneWhenNull() {
    InvoiceRequest request = new InvoiceRequest(
        1L, null, "Venda de producao rural",
        OffsetDateTime.now(), null,
        "Comprador", "12345678901",
        null, null,
        List.of(buildItem("1", "10.00")), null);

    Invoice invoice = InvoiceMapper.toEntity(request, producer);

    assertThat(invoice.getSeries()).isEqualTo(1);
  }

  @Test
  @DisplayName("toEntity deve preservar serie informada quando serie nao e null no request")
  void toEntityShouldPreserveSerieWhenProvided() {
    InvoiceRequest request = new InvoiceRequest(
        1L, 3, "Venda de producao rural",
        OffsetDateTime.now(), null,
        "Comprador", "12345678901",
        null, null,
        List.of(buildItem("1", "10.00")), null);

    Invoice invoice = InvoiceMapper.toEntity(request, producer);

    assertThat(invoice.getSeries()).isEqualTo(3);
  }

  @Test
  @DisplayName("toEntity deve vincular o produtor passado como parametro")
  void toEntityShouldLinkProvidedProdutor() {
    InvoiceRequest request = buildRequest(List.of(buildItem("1", "10.00")));

    Invoice invoice = InvoiceMapper.toEntity(request, producer);

    assertThat(invoice.getProducer()).isSameAs(producer);
    assertThat(invoice.getProducer().getId()).isEqualTo(producerId);
  }

  // -------------------------------------------------------------------------
  // toEntity — calculo de totalValue por item com arredondamento HALF_UP
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("toEntity deve calcular valorTotal do item como quantidade * valorUnitario HALF_UP 2dp")
  void toEntityShouldCalculateItemValueWithHalfUpRounding() {
    // 3 * 1.005 = 3.015 → HALF_UP 2dp = 3.02
    InvoiceItemRequest item = buildItem("3", "1.005");
    InvoiceRequest request = buildRequest(List.of(item));

    Invoice invoice = InvoiceMapper.toEntity(request, producer);

    assertThat(invoice.getItems()).hasSize(1);
    assertThat(invoice.getItems().get(0).getTotalValue()).isEqualByComparingTo("3.02");
  }

  @Test
  @DisplayName("toEntity deve arredondar .5 para cima com HALF_UP (0.5 * 0.1 = 0.05 → 0.05)")
  void toEntityHalfUpRoundingAtExactHalf() {
    // 1 * 0.055 = 0.055 → HALF_UP 2dp = 0.06
    InvoiceItemRequest item = buildItem("1", "0.055");
    InvoiceRequest request = buildRequest(List.of(item));

    Invoice invoice = InvoiceMapper.toEntity(request, producer);

    assertThat(invoice.getItems().get(0).getTotalValue()).isEqualByComparingTo("0.06");
  }

  @Test
  @DisplayName("toEntity deve calcular valorTotal exato sem arredondamento para valores inteiros")
  void toEntityShouldCalculateExactValueForWholeNumbers() {
    // 500 * 2.00 = 1000.00
    InvoiceItemRequest item = buildItem("500", "2.00");
    InvoiceRequest request = buildRequest(List.of(item));

    Invoice invoice = InvoiceMapper.toEntity(request, producer);

    assertThat(invoice.getItems().get(0).getTotalValue()).isEqualByComparingTo("1000.00");
  }

  // -------------------------------------------------------------------------
  // toEntity — totalProductsValue e totalInvoiceValue
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("toEntity deve calcular valorTotalProdutos como soma dos valorTotal dos itens")
  void toEntityShouldCalculateTotalProdutosAsSumOfItemTotals() {
    // Item 1: 10 * 2.50 = 25.00
    // Item 2: 5 * 4.00 = 20.00
    // Total = 45.00
    InvoiceItemRequest item1 = buildItem("10", "2.50");
    InvoiceItemRequest item2 = buildItem("5", "4.00");
    InvoiceRequest request = buildRequest(List.of(item1, item2));

    Invoice invoice = InvoiceMapper.toEntity(request, producer);

    assertThat(invoice.getTotalProductsValue()).isEqualByComparingTo("45.00");
  }

  @Test
  @DisplayName("toEntity deve igualar valorTotalNota a valorTotalProdutos (Sprint 2 sem impostos)")
  void toEntityShouldSetValorTotalNotaEqualToValorTotalProdutos() {
    InvoiceRequest request = buildRequest(List.of(buildItem("100", "5.75")));

    Invoice invoice = InvoiceMapper.toEntity(request, producer);

    assertThat(invoice.getTotalInvoiceValue()).isEqualByComparingTo(invoice.getTotalProductsValue());
  }

  @Test
  @DisplayName("toEntity com multiplos itens deve somar corretamente tres itens distintos")
  void toEntityShouldSumThreeItemsCorrectly() {
    // 1*10 + 2*5 + 3*3.33 = 10 + 10 + 9.99 = 29.99
    InvoiceItemRequest i1 = buildItem("1", "10.00");
    InvoiceItemRequest i2 = buildItem("2", "5.00");
    InvoiceItemRequest i3 = buildItem("3", "3.33");
    InvoiceRequest request = buildRequest(List.of(i1, i2, i3));

    Invoice invoice = InvoiceMapper.toEntity(request, producer);

    assertThat(invoice.getTotalProductsValue()).isEqualByComparingTo("29.99");
    assertThat(invoice.getTotalInvoiceValue()).isEqualByComparingTo("29.99");
  }

  // -------------------------------------------------------------------------
  // toEntity — endereco null e preenchido
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("toEntity deve mapear destinatarioEndereco como null quando request traz null")
  void toEntityShouldMapNullEnderecoAsNull() {
    InvoiceRequest request = buildRequest(List.of(buildItem("1", "10.00")));
    // buildRequest ja passa endereco null

    Invoice invoice = InvoiceMapper.toEntity(request, producer);

    assertThat(invoice.getRecipientAddress()).isNull();
  }

  @Test
  @DisplayName("toEntity deve mapear todos os campos do endereco quando preenchido")
  void toEntityShouldMapEnderecoFieldsCorrectly() {
    InvoiceRequest.RecipientAddressRequest enderecoReq =
        new InvoiceRequest.RecipientAddressRequest(
            "Rua das Flores", "100", "Cuiaba", "5103403", "MT", "78000000");
    InvoiceRequest request = new InvoiceRequest(
        1L, 1, "Venda de producao rural",
        OffsetDateTime.now(), null,
        "Comprador", "12345678901",
        null, enderecoReq,
        List.of(buildItem("1", "10.00")), null);

    Invoice invoice = InvoiceMapper.toEntity(request, producer);

    RecipientAddress endereco = invoice.getRecipientAddress();
    assertThat(endereco).isNotNull();
    assertThat(endereco.getStreet()).isEqualTo("Rua das Flores");
    assertThat(endereco.getNumber()).isEqualTo("100");
    assertThat(endereco.getCity()).isEqualTo("Cuiaba");
    assertThat(endereco.getIbgeCityCode()).isEqualTo("5103403");
    assertThat(endereco.getUf()).isEqualTo("MT");
    assertThat(endereco.getZipCode()).isEqualTo("78000000");
  }

  // -------------------------------------------------------------------------
  // toResponse — mapeamento completo da entidade
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("toResponse deve mapear todos os campos escalares da entidade corretamente")
  void toResponseShouldMapAllScalarFields() {
    UUID invoiceId = UUID.randomUUID();
    OffsetDateTime issuanceDate = OffsetDateTime.now();
    OffsetDateTime createdAt = OffsetDateTime.now();
    OffsetDateTime updatedAt = OffsetDateTime.now();

    Invoice invoice = Invoice.builder()
        .id(invoiceId)
        .accessKey(null)
        .number(42L)
        .series(2)
        .model("55")
        .operationNature("Venda de producao rural")
        .issuanceDate(issuanceDate)
        .exitDate(null)
        .producer(producer)
        .recipientName("Comprador X")
        .recipientTaxId("12345678901234")
        .recipientStateRegistration("IE-001")
        .recipientAddress(null)
        .totalProductsValue(new BigDecimal("250.00"))
        .totalInvoiceValue(new BigDecimal("250.00"))
        .status(InvoiceStatus.RASCUNHO)
        .createdAt(createdAt)
        .updatedAt(updatedAt)
        .build();

    InvoiceResponse response = InvoiceMapper.toResponse(invoice);

    assertThat(response.id()).isEqualTo(invoiceId);
    assertThat(response.accessKey()).isNull();
    assertThat(response.number()).isEqualTo(42L);
    assertThat(response.series()).isEqualTo(2);
    assertThat(response.model()).isEqualTo("55");
    assertThat(response.operationNature()).isEqualTo("Venda de producao rural");
    assertThat(response.status()).isEqualTo(InvoiceStatus.RASCUNHO);
    assertThat(response.issuanceDate()).isEqualTo(issuanceDate);
    assertThat(response.exitDate()).isNull();
    assertThat(response.producerId()).isEqualTo(producerId);
    assertThat(response.recipientName()).isEqualTo("Comprador X");
    assertThat(response.recipientTaxId()).isEqualTo("12345678901234");
    assertThat(response.recipientStateRegistration()).isEqualTo("IE-001");
    assertThat(response.recipientAddress()).isNull();
    assertThat(response.totalProductsValue()).isEqualByComparingTo("250.00");
    assertThat(response.totalInvoiceValue()).isEqualByComparingTo("250.00");
    assertThat(response.createdAt()).isEqualTo(createdAt);
    assertThat(response.updatedAt()).isEqualTo(updatedAt);
  }

  @Test
  @DisplayName("toResponse deve mapear itens da nota com valorTotal correto")
  void toResponseShouldMapItemsWithCorrectValorTotal() {
    InvoiceItem item = InvoiceItem.builder()
        .id(UUID.randomUUID())
        .itemNumber(1)
        .productCode("P001")
        .description("Soja em graos")
        .ncm("12010090")
        .cfop("6101")
        .unit("KG")
        .quantity(new BigDecimal("500.0000"))
        .unitPrice(new BigDecimal("2.5000"))
        .totalValue(new BigDecimal("1250.00"))
        .createdAt(OffsetDateTime.now())
        .updatedAt(OffsetDateTime.now())
        .build();

    Invoice invoice = buildNotaComItem(item);

    InvoiceResponse response = InvoiceMapper.toResponse(invoice);

    assertThat(response.items()).hasSize(1);
    InvoiceItemResponse itemResponse = response.items().get(0);
    assertThat(itemResponse.description()).isEqualTo("Soja em graos");
    assertThat(itemResponse.quantity()).isEqualByComparingTo("500.0000");
    assertThat(itemResponse.unitPrice()).isEqualByComparingTo("2.5000");
    assertThat(itemResponse.totalValue()).isEqualByComparingTo("1250.00");
    assertThat(itemResponse.productCode()).isEqualTo("P001");
  }

  @Test
  @DisplayName("toResponse deve retornar endereco preenchido quando entidade tem endereco")
  void toResponseShouldMapEnderecoWhenPresent() {
    RecipientAddress endereco = RecipientAddress.builder()
        .street("Av. Central")
        .number("200")
        .city("Sorriso")
        .ibgeCityCode("5107925")
        .uf("MT")
        .zipCode("78890000")
        .build();

    Invoice invoice = buildNotaComItem(buildItemEntity());
    invoice.setRecipientAddress(endereco);

    InvoiceResponse response = InvoiceMapper.toResponse(invoice);

    assertThat(response.recipientAddress()).isNotNull();
    assertThat(response.recipientAddress().street()).isEqualTo("Av. Central");
    assertThat(response.recipientAddress().city()).isEqualTo("Sorriso");
    assertThat(response.recipientAddress().uf()).isEqualTo("MT");
    assertThat(response.recipientAddress().zipCode()).isEqualTo("78890000");
  }

  @Test
  @DisplayName("toResponse com lista de itens vazia deve retornar lista vazia na response")
  void toResponseWithNoItemsShouldReturnEmptyItemList() {
    Invoice invoice = Invoice.builder()
        .id(UUID.randomUUID())
        .number(1L)
        .series(1)
        .model("55")
        .operationNature("Venda")
        .issuanceDate(OffsetDateTime.now())
        .producer(producer)
        .recipientName("Comprador")
        .recipientTaxId("12345678901")
        .recipientAddress(null)
        .totalProductsValue(BigDecimal.ZERO)
        .totalInvoiceValue(BigDecimal.ZERO)
        .status(InvoiceStatus.RASCUNHO)
        .build();

    InvoiceResponse response = InvoiceMapper.toResponse(invoice);

    assertThat(response.items()).isEmpty();
  }

  // -------------------------------------------------------------------------
  // toItemEntityList — conversao de lista de requests em entidades
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("toItemEntityList deve converter cada request em entidade com valorTotal calculado")
  void toItemEntityListShouldConvertEachRequestWithCalculatedTotal() {
    InvoiceItemRequest i1 = buildItem("10", "5.00");
    InvoiceItemRequest i2 = buildItem("3", "3.333");

    List<InvoiceItem> result = InvoiceMapper.toItemEntityList(List.of(i1, i2));

    assertThat(result).hasSize(2);
    // 10 * 5.00 = 50.00
    assertThat(result.get(0).getTotalValue()).isEqualByComparingTo("50.00");
    // 3 * 3.333 = 9.999 → HALF_UP 2dp = 10.00
    assertThat(result.get(1).getTotalValue()).isEqualByComparingTo("10.00");
  }

  @Test
  @DisplayName("toItemEntityList deve retornar itens sem associacao com nota (notaFiscal null)")
  void toItemEntityListShouldReturnItemsWithNoNotaFiscalAssociation() {
    List<InvoiceItem> result =
        InvoiceMapper.toItemEntityList(List.of(buildItem("1", "1.00")));

    assertThat(result).hasSize(1);
    assertThat(result.get(0).getInvoice()).isNull();
  }

  // -------------------------------------------------------------------------
  // documentType — default NFE e round-trip
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("toEntity usa NFE como default quando tipoDocumento e null")
  void toEntityDefaultNfe() {
    InvoiceRequest request = buildRequest(List.of(buildItem("1", "10.00")));
    // buildRequest passa documentType = null

    Invoice invoice = InvoiceMapper.toEntity(request, producer);

    assertThat(invoice.getDocumentType()).isEqualTo(DocumentType.NFE);
  }

  @Test
  @DisplayName("toResponse expoe o tipoDocumento da entidade")
  void toResponseExpoeTipoDocumento() {
    InvoiceRequest request = new InvoiceRequest(
        1L, 1, "Venda de producao rural",
        OffsetDateTime.now(), null,
        "Comprador", "12345678901",
        null, null,
        List.of(buildItem("1", "10.00")), DocumentType.NFA);

    Invoice invoice = InvoiceMapper.toEntity(request, producer);

    InvoiceResponse response = InvoiceMapper.toResponse(invoice);

    assertThat(response.documentType()).isEqualTo(DocumentType.NFA);
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private InvoiceRequest buildRequest(List<InvoiceItemRequest> items) {
    return new InvoiceRequest(
        1L,
        1,
        "Venda de producao rural",
        OffsetDateTime.now(),
        null,
        "Comprador Teste",
        "12345678901",
        null,
        null,
        items,
        null);
  }

  /**
   * Cria um {@link InvoiceItemRequest} simples com o itemNumber=1 padrao.
   * quantity e unitPrice sao passados como strings para facilitar casos de borda.
   */
  private InvoiceItemRequest buildItem(String quantity, String unitPrice) {
    return new InvoiceItemRequest(
        1,
        null,
        "Soja em graos",
        "12010090",
        "6101",
        "KG",
        new BigDecimal(quantity),
        new BigDecimal(unitPrice),
        null, null, null);
  }

  private InvoiceItem buildItemEntity() {
    return InvoiceItem.builder()
        .id(UUID.randomUUID())
        .itemNumber(1)
        .description("Soja em graos")
        .ncm("12010090")
        .cfop("6101")
        .unit("KG")
        .quantity(new BigDecimal("100.0000"))
        .unitPrice(new BigDecimal("3.0000"))
        .totalValue(new BigDecimal("300.00"))
        .createdAt(OffsetDateTime.now())
        .updatedAt(OffsetDateTime.now())
        .build();
  }

  private Invoice buildNotaComItem(InvoiceItem item) {
    Invoice invoice = Invoice.builder()
        .id(UUID.randomUUID())
        .number(1L)
        .series(1)
        .model("55")
        .operationNature("Venda de producao rural")
        .issuanceDate(OffsetDateTime.now())
        .producer(producer)
        .recipientName("Comprador")
        .recipientTaxId("12345678901")
        .recipientAddress(null)
        .totalProductsValue(item.getTotalValue())
        .totalInvoiceValue(item.getTotalValue())
        .status(InvoiceStatus.RASCUNHO)
        .createdAt(OffsetDateTime.now())
        .updatedAt(OffsetDateTime.now())
        .build();
    invoice.addItem(item);
    return invoice;
  }
}
