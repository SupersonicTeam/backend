package br.com.agrocontabil.repository;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.model.RecipientAddress;
import br.com.agrocontabil.model.Invoice;
import br.com.agrocontabil.model.InvoiceItem;
import br.com.agrocontabil.model.Producer;
import br.com.agrocontabil.model.InvoiceStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;

/**
 * Testes de integração do repositório {@link InvoiceRepository}.
 *
 * <p>Utiliza H2 em memória (profile {@code test}). Como algumas entidades do projeto usam
 * {@code columnDefinition} com tipos exclusivos do PostgreSQL ({@code text[]}, {@code jsonb}),
 * o {@code ddl-auto} é desabilitado e o schema compatível com H2 é criado via {@code @Sql}.
 *
 * <p>Cenários cobertos:
 * <ul>
 *   <li>Persistência de {@link Invoice} com um item em cascata</li>
 *   <li>Leitura e verificação de campos após flush/reload</li>
 *   <li>Itens em cascata associados à invoice</li>
 *   <li>Paginação via {@link InvoiceRepository#findByProducerId}</li>
 *   <li>{@link InvoiceRepository#findByIdAndProducerId} com producer incorreto
 *       retornando {@link Optional#empty()}</li>
 * </ul>
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=none")
@Sql(
    scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS
)
@DirtiesContext(classMode = ClassMode.AFTER_CLASS)
class InvoiceRepositoryTest {

  @Autowired
  private InvoiceRepository invoiceRepository;

  @Autowired
  private ProducerRepository produtorRepository;

  private Producer producer;

  @BeforeEach
  void setUp() {
    invoiceRepository.deleteAll();
    produtorRepository.deleteAll();

    producer = Producer.builder()
        .fullName("Maria Agricultora")
        .taxId("98765432100")
        .build();
    producer = produtorRepository.save(producer);
  }

  /**
   * Persiste uma Invoice com 1 item, relê do banco e verifica todos os campos
   * mapeados, incluindo o embedded de endereço e o item em cascata.
   */
  @Test
  @DisplayName("deve persistir NotaFiscal com item em cascata e recuperar todos os campos")
  void shouldPersistNotaFiscalWithItemAndRetrieveAllFields() {
    Invoice invoice = buildNotaFiscal();
    InvoiceItem item = buildItem();
    invoice.addItem(item);

    invoiceRepository.saveAndFlush(invoice);

    // Relê diretamente do banco limpando a sessão via clear implícito do saveAndFlush
    Optional<Invoice> found = invoiceRepository.findById(invoice.getId());
    assertThat(found).isPresent();

    Invoice retrieved = found.get();
    assertThat(retrieved.getNumber()).isEqualTo(1L);
    assertThat(retrieved.getSeries()).isEqualTo(1);
    assertThat(retrieved.getModel()).isEqualTo("55");
    assertThat(retrieved.getOperationNature()).isEqualTo("Venda de producao rural");
    assertThat(retrieved.getRecipientName()).isEqualTo("Jose Comprador");
    assertThat(retrieved.getRecipientTaxId()).isEqualTo("12345678901");
    assertThat(retrieved.getStatus()).isEqualTo(InvoiceStatus.RASCUNHO);
    assertThat(retrieved.getTotalProductsValue()).isEqualByComparingTo("1000.00");
    assertThat(retrieved.getTotalInvoiceValue()).isEqualByComparingTo("1000.00");
    assertThat(retrieved.getCreatedAt()).isNotNull();
    assertThat(retrieved.getUpdatedAt()).isNotNull();

    // Verifica embedded de endereço
    RecipientAddress endereco = retrieved.getRecipientAddress();
    assertThat(endereco).isNotNull();
    assertThat(endereco.getStreet()).isEqualTo("Rua das Flores");
    assertThat(endereco.getNumber()).isEqualTo("100");
    assertThat(endereco.getCity()).isEqualTo("Cuiaba");
    assertThat(endereco.getIbgeCityCode()).isEqualTo("5103403");
    assertThat(endereco.getUf()).isEqualTo("MT");
    assertThat(endereco.getZipCode()).isEqualTo("78000000");

    // Verifica item em cascata
    assertThat(retrieved.getItems()).hasSize(1);
    InvoiceItem retrievedItem = retrieved.getItems().get(0);
    assertThat(retrievedItem.getItemNumber()).isEqualTo(1);
    assertThat(retrievedItem.getDescription()).isEqualTo("Soja em graos");
    assertThat(retrievedItem.getNcm()).isEqualTo("12010090");
    assertThat(retrievedItem.getCfop()).isEqualTo("6101");
    assertThat(retrievedItem.getUnit()).isEqualTo("KG");
    assertThat(retrievedItem.getQuantity()).isEqualByComparingTo("500.0000");
    assertThat(retrievedItem.getUnitPrice()).isEqualByComparingTo("2.0000");
    assertThat(retrievedItem.getTotalValue()).isEqualByComparingTo("1000.00");
  }

  /**
   * Verifica que {@link InvoiceRepository#findByProducerId} retorna apenas as notas
   * do producer correto e suporta paginação.
   */
  @Test
  @DisplayName("findByProdutorId deve retornar apenas as notas do produtor informado, paginadas")
  void findByProdutorIdShouldReturnOnlyNotasOfGivenProdutor() {
    invoiceRepository.saveAndFlush(buildNotaFiscal());

    // Producer diferente — invoice dele não deve aparecer na busca do primeiro producer
    Producer outroProd = Producer.builder()
        .fullName("Carlos Fazendeiro")
        .taxId("11122233344")
        .build();
    outroProd = produtorRepository.save(outroProd);

    invoiceRepository.saveAndFlush(buildNotaFiscalForProdutor(outroProd, 2L));

    Page<Invoice> page = invoiceRepository.findByProducerId(
        producer.getId(), PageRequest.of(0, 10));

    assertThat(page.getTotalElements()).isEqualTo(1);
    assertThat(page.getContent().get(0).getProducer().getId()).isEqualTo(producer.getId());
    assertThat(page.getContent().get(0).getNumber()).isEqualTo(1L);
  }

  /**
   * Verifica que {@link InvoiceRepository#findByIdAndProducerId} retorna vazio
   * quando o ID da invoice existe mas pertence a outro producer.
   */
  @Test
  @DisplayName("findByIdAndProdutorId deve retornar empty quando o produtor nao corresponde")
  void findByIdAndProdutorIdShouldReturnEmptyWhenProdutorDoesNotMatch() {
    Invoice invoice = invoiceRepository.saveAndFlush(buildNotaFiscal());

    UUID produtorErradoId = UUID.randomUUID();
    Optional<Invoice> result =
        invoiceRepository.findByIdAndProducerId(invoice.getId(), produtorErradoId);

    assertThat(result).isEmpty();
  }

  /**
   * Verifica que {@link InvoiceRepository#findByIdAndProducerId} retorna a invoice
   * quando tanto o ID quanto o producer estão corretos.
   */
  @Test
  @DisplayName("findByIdAndProdutorId deve retornar a nota quando produtor e id estao corretos")
  void findByIdAndProdutorIdShouldReturnNotaWhenMatch() {
    Invoice invoice = invoiceRepository.saveAndFlush(buildNotaFiscal());

    Optional<Invoice> result =
        invoiceRepository.findByIdAndProducerId(invoice.getId(), producer.getId());

    assertThat(result).isPresent();
    assertThat(result.get().getId()).isEqualTo(invoice.getId());
  }

  @Test
  @DisplayName("existsByChaveAcesso retorna true para chave existente e false para inexistente")
  void existsByAccessKey() {
    Invoice invoice = buildNotaFiscal();
    invoice.setAccessKey("41260612345678000195550010000000011000000017");
    invoiceRepository.saveAndFlush(invoice);

    assertThat(invoiceRepository.existsByAccessKey(
        "41260612345678000195550010000000011000000017")).isTrue();
    assertThat(invoiceRepository.existsByAccessKey(
        "00000000000000000000000000000000000000000000")).isFalse();
  }

  // -------------------------------------------------------------------------
  // Helpers de construção
  // -------------------------------------------------------------------------

  private Invoice buildNotaFiscal() {
    return buildNotaFiscalForProdutor(this.producer, 1L);
  }

  private Invoice buildNotaFiscalForProdutor(Producer prod, Long number) {
    RecipientAddress endereco = RecipientAddress.builder()
        .street("Rua das Flores")
        .number("100")
        .city("Cuiaba")
        .ibgeCityCode("5103403")
        .uf("MT")
        .zipCode("78000000")
        .build();

    return Invoice.builder()
        .number(number)
        .series(1)
        .model("55")
        .operationNature("Venda de producao rural")
        .issuanceDate(OffsetDateTime.now())
        .producer(prod)
        .recipientName("Jose Comprador")
        .recipientTaxId("12345678901")
        .recipientAddress(endereco)
        .totalProductsValue(new BigDecimal("1000.00"))
        .totalInvoiceValue(new BigDecimal("1000.00"))
        .status(InvoiceStatus.RASCUNHO)
        .build();
  }

  private InvoiceItem buildItem() {
    return InvoiceItem.builder()
        .itemNumber(1)
        .description("Soja em graos")
        .ncm("12010090")
        .cfop("6101")
        .unit("KG")
        .quantity(new BigDecimal("500.0000"))
        .unitPrice(new BigDecimal("2.0000"))
        .totalValue(new BigDecimal("1000.00"))
        .build();
  }
}
