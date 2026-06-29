package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import br.com.agrocontabil.dto.InvoiceValidationResponse;
import br.com.agrocontabil.exception.ResourceNotFoundException;
import br.com.agrocontabil.model.Invoice;
import br.com.agrocontabil.model.InvoiceValidation;
import br.com.agrocontabil.model.Producer;
import br.com.agrocontabil.model.InvoiceStatus;
import br.com.agrocontabil.repository.InvoiceRepository;
import br.com.agrocontabil.repository.InvoiceValidationRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Testes unitarios do {@link InvoiceValidationService} (issue #22).
 *
 * <p>Mockito puro: o {@link InvoiceRepository}, o {@link InvoiceValidationRepository}
 * e o {@link AuthenticatedProducerProvider} sao mockados.
 *
 * <p>Cenarios cobertos:
 * <ul>
 *   <li>recordInconsistencies — persiste lote unico e marca status COM_INCONSISTENCIAS</li>
 *   <li>recordInconsistencies — lista vazia/nula nao toca o banco</li>
 *   <li>recordInconsistencies — invoice inexistente lanca ResourceNotFoundException</li>
 *   <li>listValidations — retorna historico mapeado respeitando ownership</li>
 *   <li>listValidations — invoice de outro producer/inexistente lanca 404 (anti-IDOR)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class InvoiceValidationServiceTest {

  @Mock
  private InvoiceRepository invoiceRepository;

  @Mock
  private InvoiceValidationRepository validacaoRepository;

  @Mock
  private AuthenticatedProducerProvider producerProvider;

  @InjectMocks
  private InvoiceValidationService validationService;

  @Captor
  private ArgumentCaptor<List<InvoiceValidation>> registrosCaptor;

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
  // recordInconsistencies
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("registrarInconsistencias deve persistir um unico lote e marcar status COM_INCONSISTENCIAS")
  void registrarInconsistenciasShouldPersistBatchAndUpdateStatus() {
    UUID invoiceId = UUID.randomUUID();
    Invoice invoice = Invoice.builder()
        .id(invoiceId)
        .status(InvoiceStatus.RASCUNHO)
        .build();

    List<ErrorDetail> errors = List.of(
        new ErrorDetail("NOTA_INVALIDA_EMISSAO", "item[1].ncm",
            "NCM e obrigatorio", "Informe o NCM", "1234567", "8 digitos"),
        ErrorDetail.of(ErrorCode.NOTA_INVALIDA_EMISSAO, "destinatarioNome",
            "Nome do destinatario e obrigatorio"));

    when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice));

    validationService.recordInconsistencies(invoiceId, errors);

    verify(validacaoRepository).saveAll(registrosCaptor.capture());
    List<InvoiceValidation> registros = registrosCaptor.getValue();

    assertThat(registros).hasSize(2);
    // Mesmo lote e mesmo timestamp para todas as inconsistencias da tentativa
    UUID lote = registros.get(0).getValidationBatch();
    assertThat(lote).isNotNull();
    assertThat(registros).allSatisfy(r -> {
      assertThat(r.getValidationBatch()).isEqualTo(lote);
      assertThat(r.getInvoice()).isSameAs(invoice);
      assertThat(r.getValidatedAt()).isNotNull();
    });
    // Primeiro registro preserva todos os campos do ErrorDetail
    InvoiceValidation primeiro = registros.get(0);
    assertThat(primeiro.getCode()).isEqualTo("NOTA_INVALIDA_EMISSAO");
    assertThat(primeiro.getField()).isEqualTo("item[1].ncm");
    assertThat(primeiro.getMessage()).isEqualTo("NCM e obrigatorio");
    assertThat(primeiro.getSuggestion()).isEqualTo("Informe o NCM");
    assertThat(primeiro.getRejectedValue()).isEqualTo("1234567");
    assertThat(primeiro.getExpectedValue()).isEqualTo("8 digitos");

    // Status da invoice atualizado e persistido
    assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.COM_INCONSISTENCIAS);
    verify(invoiceRepository).save(invoice);
  }

  @Test
  @DisplayName("registrarInconsistencias com lista vazia nao deve tocar o banco")
  void registrarInconsistenciasWithEmptyListShouldBeNoOp() {
    validationService.recordInconsistencies(UUID.randomUUID(), List.of());

    verifyNoInteractions(invoiceRepository, validacaoRepository);
  }

  @Test
  @DisplayName("registrarInconsistencias com lista nula nao deve tocar o banco")
  void registrarInconsistenciasWithNullListShouldBeNoOp() {
    validationService.recordInconsistencies(UUID.randomUUID(), null);

    verifyNoInteractions(invoiceRepository, validacaoRepository);
  }

  @Test
  @DisplayName("registrarInconsistencias deve lancar ResourceNotFoundException quando a nota nao existe")
  void registrarInconsistenciasShouldThrowWhenNotaNotFound() {
    UUID invoiceId = UUID.randomUUID();
    List<ErrorDetail> errors = List.of(
        ErrorDetail.of(ErrorCode.XML_GENERATION_ERROR, "Falha na geracao"));

    when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> validationService.recordInconsistencies(invoiceId, errors))
        .isInstanceOf(ResourceNotFoundException.class)
        .hasMessageContaining(invoiceId.toString());

    verify(validacaoRepository, never()).saveAll(any());
  }

  // -------------------------------------------------------------------------
  // listValidations
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("listarValidacoes deve retornar o historico mapeado quando a nota pertence ao produtor")
  void listarValidacoesShouldReturnMappedHistory() {
    UUID invoiceId = UUID.randomUUID();
    UUID lote = UUID.randomUUID();
    Invoice invoice = Invoice.builder().id(invoiceId).build();

    InvoiceValidation validacao = InvoiceValidation.builder()
        .id(UUID.randomUUID())
        .invoice(invoice)
        .validationBatch(lote)
        .code("NOTA_INVALIDA_EMISSAO")
        .field("item[1].ncm")
        .message("NCM e obrigatorio")
        .validatedAt(OffsetDateTime.now())
        .build();

    when(producerProvider.get()).thenReturn(producer);
    when(invoiceRepository.findByIdAndProducerId(invoiceId, producerId))
        .thenReturn(Optional.of(invoice));
    when(validacaoRepository.findByInvoiceIdOrderByValidatedAtDesc(invoiceId))
        .thenReturn(List.of(validacao));

    List<InvoiceValidationResponse> historico = validationService.listValidations(invoiceId);

    assertThat(historico).hasSize(1);
    InvoiceValidationResponse response = historico.get(0);
    assertThat(response.validationBatch()).isEqualTo(lote);
    assertThat(response.code()).isEqualTo("NOTA_INVALIDA_EMISSAO");
    assertThat(response.field()).isEqualTo("item[1].ncm");
    assertThat(response.message()).isEqualTo("NCM e obrigatorio");
  }

  @Test
  @DisplayName("listarValidacoes deve retornar lista vazia quando a nota nunca teve inconsistencias")
  void listarValidacoesShouldReturnEmptyListWhenNoInconsistencias() {
    UUID invoiceId = UUID.randomUUID();
    Invoice invoice = Invoice.builder().id(invoiceId).build();

    when(producerProvider.get()).thenReturn(producer);
    when(invoiceRepository.findByIdAndProducerId(invoiceId, producerId))
        .thenReturn(Optional.of(invoice));
    when(validacaoRepository.findByInvoiceIdOrderByValidatedAtDesc(invoiceId))
        .thenReturn(List.of());

    List<InvoiceValidationResponse> historico = validationService.listValidations(invoiceId);

    assertThat(historico).isEmpty();
  }

  @Test
  @DisplayName("listarValidacoes deve lancar 404 quando a nota nao existe ou pertence a outro produtor")
  void listarValidacoesShouldThrowWhenNotaNotFound() {
    UUID invoiceId = UUID.randomUUID();

    when(producerProvider.get()).thenReturn(producer);
    when(invoiceRepository.findByIdAndProducerId(invoiceId, producerId))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> validationService.listValidations(invoiceId))
        .isInstanceOf(ResourceNotFoundException.class)
        .hasMessageContaining(invoiceId.toString());

    verify(validacaoRepository, never()).findByInvoiceIdOrderByValidatedAtDesc(any());
  }
}
