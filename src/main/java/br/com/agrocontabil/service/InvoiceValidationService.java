package br.com.agrocontabil.service;

import br.com.agrocontabil.common.ErrorDetail;
import br.com.agrocontabil.dto.InvoiceValidationResponse;
import br.com.agrocontabil.exception.ResourceNotFoundException;
import br.com.agrocontabil.mapper.InvoiceMapper;
import br.com.agrocontabil.model.Invoice;
import br.com.agrocontabil.model.InvoiceValidation;
import br.com.agrocontabil.model.Producer;
import br.com.agrocontabil.model.InvoiceStatus;
import br.com.agrocontabil.repository.InvoiceRepository;
import br.com.agrocontabil.repository.InvoiceValidationRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Serviço de persistência e consulta do histórico de inconsistências de validação da NF-e
 * (issue #22, camada C do design da #17).
 *
 * <p>Responsabilidades:
 * <ul>
 *   <li><strong>Registrar</strong> as inconsistências encontradas ao validate/generate o XML,
 *       reaproveitando os {@link ErrorDetail} da camada A, e marcar a invoice como
 *       {@link InvoiceStatus#COM_INCONSISTENCIAS}.</li>
 *   <li><strong>Listar</strong> o histórico de uma invoice, respeitando a propriedade do
 *       producer autenticado (anti-IDOR 404).</li>
 * </ul>
 */
@Service
public class InvoiceValidationService {

  private static final Logger log = LoggerFactory.getLogger(InvoiceValidationService.class);

  private final InvoiceRepository invoiceRepository;
  private final InvoiceValidationRepository validacaoRepository;
  private final AuthenticatedProducerProvider producerProvider;

  public InvoiceValidationService(
      InvoiceRepository invoiceRepository,
      InvoiceValidationRepository validacaoRepository,
      AuthenticatedProducerProvider producerProvider) {
    this.invoiceRepository = invoiceRepository;
    this.validacaoRepository = validacaoRepository;
    this.producerProvider = producerProvider;
  }

  /**
   * Registra um lote de inconsistências para a invoice e a marca como
   * {@link InvoiceStatus#COM_INCONSISTENCIAS}.
   *
   * <p><strong>Transação independente ({@link Propagation#REQUIRES_NEW}):</strong> este método
   * é chamado de dentro do fluxo de geração de XML, que relança a exceção de validação (HTTP
   * 422) e, portanto, faz rollback da sua própria transação. Persistir o histórico numa
   * transação separada garante que o registro das inconsistências sobreviva a esse rollback.
   *
   * <p>Não verifica propriedade: a checagem anti-IDOR já ocorreu no chamador, que carregou a
   * invoice via {@code findByIdAndProducerId}. Aqui a invoice é recarregada pelo ID para ser
   * gerenciada na nova transação.
   *
   * @param invoiceId identificador da invoice fiscal cujas inconsistências serão registradas
   * @param errors inconsistências encontradas (ErrorDetail da camada A); se vazio, nada é feito
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordInconsistencies(UUID invoiceId, List<ErrorDetail> errors) {
    if (errors == null || errors.isEmpty()) {
      return;
    }

    Invoice invoice = invoiceRepository
        .findById(invoiceId)
        .orElseThrow(() -> new ResourceNotFoundException("NotaFiscal", invoiceId));

    UUID lote = UUID.randomUUID();
    OffsetDateTime validatedAt = OffsetDateTime.now();

    List<InvoiceValidation> registros = errors.stream()
        .map(error -> InvoiceValidation.builder()
            .invoice(invoice)
            .validationBatch(lote)
            .code(error.code())
            .field(error.field())
            .message(error.message())
            .suggestion(error.suggestion())
            .rejectedValue(error.rejectedValue())
            .expectedValue(error.expectedValue())
            .validatedAt(validatedAt)
            .build())
        .toList();

    validacaoRepository.saveAll(registros);

    invoice.setStatus(InvoiceStatus.COM_INCONSISTENCIAS);
    invoiceRepository.save(invoice);

    log.info("Inconsistencias de validacao registradas: notaId={} lote={} quantidade={}",
        invoiceId, lote, registros.size());
  }

  /**
   * Lista o histórico de inconsistências de validação de uma invoice, da tentativa mais recente
   * para a mais antiga.
   *
   * <p>Ownership anti-IDOR: a invoice é buscada via {@code findByIdAndProducerId} — retorna 404
   * tanto se não existir quanto se pertencer a outro producer, sem revelar sua existência.
   *
   * @param invoiceId identificador da invoice fiscal
   * @return histórico de inconsistências (lista vazia se a invoice nunca teve inconsistências)
   * @throws ResourceNotFoundException se a invoice não existir ou pertencer a outro producer
   */
  @Transactional(readOnly = true)
  public List<InvoiceValidationResponse> listValidations(UUID invoiceId) {
    Producer producer = producerProvider.get();
    invoiceRepository
        .findByIdAndProducerId(invoiceId, producer.getId())
        .orElseThrow(() -> new ResourceNotFoundException("NotaFiscal", invoiceId));

    return InvoiceMapper.toValidacaoResponseList(
        validacaoRepository.findByInvoiceIdOrderByValidatedAtDesc(invoiceId));
  }
}
