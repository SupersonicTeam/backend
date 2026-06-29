package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.agrocontabil.dto.InvoiceValidationResponse;
import br.com.agrocontabil.exception.InvoiceInvalidForIssuanceException;
import br.com.agrocontabil.model.Invoice;
import br.com.agrocontabil.model.InvoiceValidation;
import br.com.agrocontabil.model.Producer;
import br.com.agrocontabil.model.InvoiceStatus;
import br.com.agrocontabil.repository.InvoiceRepository;
import br.com.agrocontabil.repository.InvoiceValidationRepository;
import br.com.agrocontabil.repository.ProducerRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;

/**
 * Testes de integração do fluxo de persistência de inconsistências (issue #22).
 *
 * <p>Diferente dos testes unitários (Mockito), este sobe o contexto Spring completo e
 * exercita a transação real: ao generate o XML de uma invoice inválida, o
 * {@link InvoiceService} faz <em>rollback</em> da sua própria transação (HTTP 422), mas o
 * histórico de inconsistências persiste graças à transação independente
 * ({@code REQUIRES_NEW}) do {@link InvoiceValidationService}. Este é o comportamento que
 * o teste unitário não consegue provar.
 *
 * <p>A classe <strong>não</strong> usa {@code @Transactional}: cada operação commita de
 * verdade — caso contrário a interação entre a transação do teste e a {@code REQUIRES_NEW}
 * mascararia o cenário. A limpeza é feita manualmente no {@link #tearDown()}.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=none")
@Sql(
    scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
@DirtiesContext(classMode = ClassMode.AFTER_CLASS)
class InvoiceValidationFlowTest {

  @Autowired
  private InvoiceService invoiceService;

  @Autowired
  private InvoiceValidationService validationService;

  @Autowired
  private InvoiceRepository invoiceRepository;

  @Autowired
  private InvoiceValidationRepository validacaoRepository;

  @Autowired
  private ProducerRepository produtorRepository;

  private Producer producer;

  @BeforeEach
  void setUp() {
    producer = produtorRepository.save(Producer.builder()
        .fullName("Maria Agricultora")
        .taxId("98765432100")
        // Sem state/IE: gera violacao no emitente, alem da invoice sem items/endereco.
        .build());

    // Simula o producer autenticado (papel do MockProducerFilter em runtime).
    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken(producer, null, List.of()));
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
    validacaoRepository.deleteAll();
    invoiceRepository.deleteAll();
    produtorRepository.deleteAll();
  }

  @Test
  @DisplayName("gerar XML de nota invalida persiste o historico e marca COM_INCONSISTENCIAS apesar do rollback")
  void gerarXmlInvalidaShouldPersistHistoryDespiteRollback() {
    Invoice invoice = invoiceRepository.save(buildNotaInvalida());
    UUID invoiceId = invoice.getId();

    assertThatThrownBy(() -> invoiceService.generateXml(invoiceId))
        .isInstanceOf(InvoiceInvalidForIssuanceException.class);

    // Apesar do rollback da transacao de generateXml, o historico sobrevive (REQUIRES_NEW)
    List<InvoiceValidation> registros =
        validacaoRepository.findByInvoiceIdOrderByValidatedAtDesc(invoiceId);
    assertThat(registros).isNotEmpty();
    // Todas as inconsistencias da tentativa compartilham o mesmo lote
    UUID lote = registros.get(0).getValidationBatch();
    assertThat(registros).allSatisfy(r -> assertThat(r.getValidationBatch()).isEqualTo(lote));

    // E o status da invoice foi atualizado e persistido
    Invoice recarregada = invoiceRepository.findById(invoiceId).orElseThrow();
    assertThat(recarregada.getStatus()).isEqualTo(InvoiceStatus.COM_INCONSISTENCIAS);
  }

  @Test
  @DisplayName("listarValidacoes retorna o historico persistido apos uma geracao que falhou")
  void listarValidacoesShouldReturnPersistedHistory() {
    Invoice invoice = invoiceRepository.save(buildNotaInvalida());
    UUID invoiceId = invoice.getId();

    assertThatThrownBy(() -> invoiceService.generateXml(invoiceId))
        .isInstanceOf(InvoiceInvalidForIssuanceException.class);

    List<InvoiceValidationResponse> historico = validationService.listValidations(invoiceId);

    assertThat(historico).isNotEmpty();
    assertThat(historico).allSatisfy(v -> {
      assertThat(v.code()).isNotBlank();
      assertThat(v.message()).isNotBlank();
      assertThat(v.validatedAt()).isNotNull();
    });
  }

  @Test
  @DisplayName("duas geracoes que falham acumulam dois lotes distintos no historico")
  void duasFalhasDevemAcumularDoisLotes() {
    Invoice invoice = invoiceRepository.save(buildNotaInvalida());
    UUID invoiceId = invoice.getId();

    assertThatThrownBy(() -> invoiceService.generateXml(invoiceId))
        .isInstanceOf(InvoiceInvalidForIssuanceException.class);
    assertThatThrownBy(() -> invoiceService.generateXml(invoiceId))
        .isInstanceOf(InvoiceInvalidForIssuanceException.class);

    List<InvoiceValidation> registros =
        validacaoRepository.findByInvoiceIdOrderByValidatedAtDesc(invoiceId);
    long lotesDistintos = registros.stream()
        .map(InvoiceValidation::getValidationBatch)
        .distinct()
        .count();

    assertThat(lotesDistintos).isEqualTo(2);
  }

  /**
   * Nota persistível (satisfaz as colunas NOT NULL) mas inválida para emissão: sem items,
   * sem endereço do destinatário e com emitente sem UF — gera múltiplas violações no
   * {@link br.com.agrocontabil.nfe.FiscalRulesValidator}.
   */
  private Invoice buildNotaInvalida() {
    return Invoice.builder()
        .number(1L)
        .series(1)
        .model("55")
        .operationNature("Venda de producao rural")
        .issuanceDate(OffsetDateTime.now())
        .producer(producer)
        .recipientName("Jose Comprador")
        .recipientTaxId("12345678901")
        .totalProductsValue(BigDecimal.ZERO)
        .totalInvoiceValue(BigDecimal.ZERO)
        .status(InvoiceStatus.RASCUNHO)
        .build();
  }
}
