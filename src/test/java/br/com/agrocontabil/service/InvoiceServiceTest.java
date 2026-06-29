package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.danfe.DanfePdfService;
import br.com.agrocontabil.dto.CancellationRequest;
import br.com.agrocontabil.dto.InvoiceItemRequest;
import br.com.agrocontabil.dto.InvoiceRequest;
import br.com.agrocontabil.dto.InvoiceResponse;
import br.com.agrocontabil.exception.BusinessException;
import br.com.agrocontabil.exception.DanfeUnavailableException;
import br.com.agrocontabil.exception.InvoiceNotEditableException;
import br.com.agrocontabil.exception.ResourceNotFoundException;
import br.com.agrocontabil.service.sefaz.SefazCancellationResult;
import br.com.agrocontabil.service.sefaz.SefazService;
import br.com.agrocontabil.model.RecipientAddress;
import br.com.agrocontabil.model.Invoice;
import br.com.agrocontabil.model.InvoiceItem;
import br.com.agrocontabil.model.Producer;
import br.com.agrocontabil.model.InvoiceStatus;
import br.com.agrocontabil.repository.InvoiceRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import br.com.agrocontabil.model.DocumentType;
import br.com.agrocontabil.service.NfaXmlService;
import br.com.agrocontabil.service.InvoiceXmlService;

/**
 * Testes unitarios do {@link InvoiceService}.
 *
 * <p>Usa Mockito puro (sem contexto Spring) — o {@link AuthenticatedProducerProvider}
 * e o {@link InvoiceRepository} sao mockados.
 *
 * <p>Cenarios cobertos:
 * <ul>
 *   <li>create — happy path: invoice criada com totais calculados e status RASCUNHO</li>
 *   <li>findById — happy path: invoice encontrada retorna DTO correto</li>
 *   <li>findById — invoice nao encontrada lanca ResourceNotFoundException</li>
 *   <li>update — bloqueado quando status nao e RASCUNHO (lanca InvoiceNotEditableException)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class InvoiceServiceTest {

  @Mock
  private InvoiceRepository invoiceRepository;

  @Mock
  private AuthenticatedProducerProvider producerProvider;

  /**
   * IbsCbsCalculationService e um colaborador real de InvoiceService.
   * Aqui usamos um mock para isolar o servico de invoice fiscal dos detalhes
   * de calculo tributario (testados isoladamente em IbsCbsCalculationServiceTest).
   * O mock retorna a propria invoice passada (identidade), permitindo que as
   * assercoes de totais de produtos continuem validas.
   */
  @Mock
  private IbsCbsCalculationService ibsCbsCalculationService;

  /**
   * InvoiceXmlService e o pipeline de geracao de XML.
   * Mockado para isolar InvoiceService dos detalhes de marshalling/XSD.
   */
  @Mock
  private InvoiceXmlService invoiceXmlService;

  @Mock
  private NfaXmlService nfaXmlService;

  @Mock
  private CfopOperationValidator cfopOperationValidator;

  @Mock
  private SefazService sefazService;

  /** Servico de geracao do PDF da DANFE (issue #14), mockado neste teste de unit. */
  @Mock
  private DanfePdfService danfePdfService;

  /** Servico de persistencia de inconsistencias (issue #22), mockado neste teste de unit. */
  @Mock
  private InvoiceValidationService validationService;

  @InjectMocks
  private InvoiceService invoiceService;

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
  // create — happy path
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("create deve persistir nota com totais calculados e retornar response com id")
  void createShouldPersistNotaAndReturnResponse() {
    when(producerProvider.get()).thenReturn(producer);

    InvoiceRequest request = buildRequest();
    Invoice notaSalva = buildNotaSalva();

    // IbsCbsCalculationService é colaborador: mock retorna a própria invoice (sem alterar campos).
    when(ibsCbsCalculationService.calculate(any(Invoice.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(invoiceRepository.save(any(Invoice.class))).thenReturn(notaSalva);

    InvoiceResponse response = invoiceService.create(request);

    assertThat(response).isNotNull();
    assertThat(response.id()).isEqualTo(notaSalva.getId());
    assertThat(response.status()).isEqualTo(InvoiceStatus.RASCUNHO);
    assertThat(response.totalProductsValue()).isEqualByComparingTo("1000.00");
    assertThat(response.totalInvoiceValue()).isEqualByComparingTo("1000.00");
    assertThat(response.producerId()).isEqualTo(producerId);

    verify(invoiceRepository).save(any(Invoice.class));
  }

  @Test
  @DisplayName("create chama o CfopOperacaoValidator antes de persistir")
  void createShouldInvokeCfopValidator() {
    when(producerProvider.get()).thenReturn(producer);
    when(ibsCbsCalculationService.calculate(any(Invoice.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(invoiceRepository.save(any(Invoice.class))).thenReturn(buildNotaSalva());

    invoiceService.create(buildRequest());

    verify(cfopOperationValidator).validate(any(Invoice.class));
  }

  @Test
  @DisplayName("create propaga CfopIncompativelException e nao persiste quando CFOP incompativel")
  void createShouldPropagateCfopIncompativel() {
    when(producerProvider.get()).thenReturn(producer);
    org.mockito.Mockito.doThrow(
            new br.com.agrocontabil.exception.IncompatibleCfopException("CFOP incompativel"))
        .when(cfopOperationValidator).validate(any(Invoice.class));

    assertThatThrownBy(() -> invoiceService.create(buildRequest()))
        .isInstanceOf(br.com.agrocontabil.exception.IncompatibleCfopException.class);

    verify(invoiceRepository, never()).save(any(Invoice.class));
  }

  // -------------------------------------------------------------------------
  // findById — happy path e not found
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("findById deve retornar DTO quando nota existe e pertence ao produtor")
  void findByIdShouldReturnResponseWhenFound() {
    UUID invoiceId = UUID.randomUUID();
    Invoice invoice = buildNotaSalva();
    invoice.setId(invoiceId);

    when(producerProvider.get()).thenReturn(producer);
    when(invoiceRepository.findByIdAndProducerId(invoiceId, producerId))
        .thenReturn(Optional.of(invoice));

    InvoiceResponse response = invoiceService.findById(invoiceId);

    assertThat(response.id()).isEqualTo(invoiceId);
    assertThat(response.number()).isEqualTo(1L);
    assertThat(response.status()).isEqualTo(InvoiceStatus.RASCUNHO);
  }

  @Test
  @DisplayName("findById deve lancar ResourceNotFoundException quando nota nao encontrada")
  void findByIdShouldThrowWhenNotFound() {
    UUID invoiceId = UUID.randomUUID();

    when(producerProvider.get()).thenReturn(producer);
    when(invoiceRepository.findByIdAndProducerId(invoiceId, producerId))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> invoiceService.findById(invoiceId))
        .isInstanceOf(ResourceNotFoundException.class)
        .hasMessageContaining(invoiceId.toString());
  }

  // -------------------------------------------------------------------------
  // update — bloqueado quando nao e RASCUNHO
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("update deve lancar NotaFiscalNaoEditavelException quando status nao e RASCUNHO")
  void updateShouldThrowWhenStatusIsNotRascunho() {
    UUID invoiceId = UUID.randomUUID();
    Invoice notaAutorizada = buildNotaSalva();
    notaAutorizada.setId(invoiceId);
    notaAutorizada.setStatus(InvoiceStatus.AUTORIZADA);

    when(producerProvider.get()).thenReturn(producer);
    when(invoiceRepository.findByIdAndProducerId(invoiceId, producerId))
        .thenReturn(Optional.of(notaAutorizada));

    InvoiceRequest request = buildRequest();

    assertThatThrownBy(() -> invoiceService.update(invoiceId, request))
        .isInstanceOf(InvoiceNotEditableException.class)
        .hasMessageContaining("AUTORIZADA");
  }

  @Test
  @DisplayName("update deve lancar NotaFiscalNaoEditavelException quando status e XML_GERADO")
  void updateShouldThrowWhenStatusIsXmlGerado() {
    UUID invoiceId = UUID.randomUUID();
    Invoice notaXmlGerado = buildNotaSalva();
    notaXmlGerado.setId(invoiceId);
    notaXmlGerado.setStatus(InvoiceStatus.XML_GERADO);

    when(producerProvider.get()).thenReturn(producer);
    when(invoiceRepository.findByIdAndProducerId(invoiceId, producerId))
        .thenReturn(Optional.of(notaXmlGerado));

    InvoiceRequest request = buildRequest();

    assertThatThrownBy(() -> invoiceService.update(invoiceId, request))
        .isInstanceOf(InvoiceNotEditableException.class)
        .hasMessageContaining(invoiceId.toString());
  }

  // -------------------------------------------------------------------------
  // findAll — retorna somente notas do producer autenticado
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("findAll deve retornar somente notas do produtor autenticado")
  void findAllShouldReturnOnlyNotasOfAuthenticatedProdutor() {
    Invoice invoice = buildNotaSalva();
    Page<Invoice> page = new PageImpl<>(List.of(invoice));
    var pageable = PageRequest.of(0, 20);

    when(producerProvider.get()).thenReturn(producer);
    when(invoiceRepository.findByProducerId(producerId, pageable)).thenReturn(page);

    Page<InvoiceResponse> result = invoiceService.findAll(pageable);

    assertThat(result.getTotalElements()).isEqualTo(1);
    assertThat(result.getContent().get(0).producerId()).isEqualTo(producerId);
    verify(invoiceRepository).findByProducerId(producerId, pageable);
  }

  // -------------------------------------------------------------------------
  // findById — invoice de outro producer deve retornar 404 (anti-IDOR)
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("findById deve lancar ResourceNotFoundException quando nota pertence a outro produtor")
  void findByIdShouldThrowWhenNotaBelongsToAnotherProdutor() {
    UUID invoiceId = UUID.randomUUID();

    when(producerProvider.get()).thenReturn(producer);
    // findByIdAndProducerId retorna vazio — simula invoice de outro producer
    when(invoiceRepository.findByIdAndProducerId(invoiceId, producerId))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> invoiceService.findById(invoiceId))
        .isInstanceOf(ResourceNotFoundException.class)
        .hasMessageContaining(invoiceId.toString());
  }

  // -------------------------------------------------------------------------
  // delete — happy path e bloqueado quando nao e RASCUNHO
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("delete deve excluir nota quando status e RASCUNHO")
  void deleteShouldDeleteNotaWhenStatusIsRascunho() {
    UUID invoiceId = UUID.randomUUID();
    Invoice invoice = buildNotaSalva();
    invoice.setId(invoiceId);

    when(producerProvider.get()).thenReturn(producer);
    when(invoiceRepository.findByIdAndProducerId(invoiceId, producerId))
        .thenReturn(Optional.of(invoice));

    invoiceService.delete(invoiceId);

    verify(invoiceRepository).delete(invoice);
  }

  @Test
  @DisplayName("delete deve lancar NotaFiscalNaoEditavelException quando status nao e RASCUNHO")
  void deleteShouldThrowWhenStatusIsNotRascunho() {
    UUID invoiceId = UUID.randomUUID();
    Invoice invoice = buildNotaSalva();
    invoice.setId(invoiceId);
    invoice.setStatus(InvoiceStatus.AUTORIZADA);

    when(producerProvider.get()).thenReturn(producer);
    when(invoiceRepository.findByIdAndProducerId(invoiceId, producerId))
        .thenReturn(Optional.of(invoice));

    assertThatThrownBy(() -> invoiceService.delete(invoiceId))
        .isInstanceOf(InvoiceNotEditableException.class)
        .hasMessageContaining("AUTORIZADA");

    verify(invoiceRepository, never()).delete(any(Invoice.class));
  }

  // -------------------------------------------------------------------------
  // update — recalculo de totais apos troca de items
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("update deve recalcular totais quando itens sao substituidos")
  void updateShouldRecalculateTotalsAfterItemReplacement() {
    UUID invoiceId = UUID.randomUUID();
    Invoice notaExistente = buildNotaSalva(); // total original = 1000.00
    notaExistente.setId(invoiceId);

    // Novo item: 10 unidades a 50.00 = 500.00
    InvoiceItemRequest novoItem = new InvoiceItemRequest(
        1,
        null,
        "Milho em graos",
        "10059010",
        "6101",
        "KG",
        new BigDecimal("10.0000"),
        new BigDecimal("50.0000"),
        null,   // cstIbsCbs
        null,   // cClassTrib
        null);  // reductionPercentage

    InvoiceRequest requestAtualizado = new InvoiceRequest(
        1L,
        1,
        "Venda de producao rural",
        OffsetDateTime.now(),
        null,
        "Jose Comprador",
        "12345678901",
        null,
        null,
        List.of(novoItem),
        null);

    when(producerProvider.get()).thenReturn(producer);
    when(invoiceRepository.findByIdAndProducerId(invoiceId, producerId))
        .thenReturn(Optional.of(notaExistente));
    when(ibsCbsCalculationService.calculate(any(Invoice.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(invoiceRepository.save(any(Invoice.class))).thenAnswer(inv -> inv.getArgument(0));

    InvoiceResponse response = invoiceService.update(invoiceId, requestAtualizado);

    assertThat(response.totalProductsValue()).isEqualByComparingTo("500.00");
    assertThat(response.totalInvoiceValue()).isEqualByComparingTo("500.00");
  }

  // -------------------------------------------------------------------------
  // create — calculos de totais com multiplos items e vinculo ao producer
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("create deve calcular valorTotal por item (quantidade * valorUnitario, HALF_UP 2dp)")
  void createShouldCalculatePerItemValueWithHalfUpRounding() {
    // Item: 3 unidades a 1.005 = 3.015 → arredondado HALF_UP em 2dp = 3.02
    InvoiceItemRequest item = new InvoiceItemRequest(
        1,
        null,
        "Produto com fracao",
        "12010090",
        "6101",
        "UN",
        new BigDecimal("3"),
        new BigDecimal("1.005"),
        null, null, null);

    InvoiceRequest request = new InvoiceRequest(
        2L,
        1,
        "Venda de producao rural",
        OffsetDateTime.now(),
        null,
        "Comprador Teste",
        "12345678901",
        null,
        null,
        List.of(item),
        null);

    when(producerProvider.get()).thenReturn(producer);
    when(invoiceRepository.save(any(Invoice.class))).thenAnswer(inv -> inv.getArgument(0));

    InvoiceResponse response = invoiceService.create(request);

    assertThat(response.items()).hasSize(1);
    assertThat(response.items().get(0).totalValue()).isEqualByComparingTo("3.02");
    assertThat(response.totalProductsValue()).isEqualByComparingTo("3.02");
    assertThat(response.totalInvoiceValue()).isEqualByComparingTo("3.02");
  }

  @Test
  @DisplayName("create com multiplos itens deve somar valorTotal corretamente")
  void createWithMultipleItemsShouldSumTotalsCorrectly() {
    // Item 1: 10 * 2.50 = 25.00
    // Item 2: 5 * 4.00 = 20.00
    // Total esperado: 45.00
    InvoiceItemRequest item1 = new InvoiceItemRequest(
        1, null, "Soja em graos", "12010090", "6101", "KG",
        new BigDecimal("10"), new BigDecimal("2.50"), null, null, null);
    InvoiceItemRequest item2 = new InvoiceItemRequest(
        2, null, "Milho em graos", "10059010", "6101", "KG",
        new BigDecimal("5"), new BigDecimal("4.00"), null, null, null);

    InvoiceRequest request = new InvoiceRequest(
        3L, 1, "Venda de producao rural",
        OffsetDateTime.now(), null,
        "Comprador Teste", "12345678901",
        null, null,
        List.of(item1, item2), null);

    when(producerProvider.get()).thenReturn(producer);
    when(invoiceRepository.save(any(Invoice.class))).thenAnswer(inv -> inv.getArgument(0));

    InvoiceResponse response = invoiceService.create(request);

    assertThat(response.items()).hasSize(2);
    assertThat(response.totalProductsValue()).isEqualByComparingTo("45.00");
    assertThat(response.totalInvoiceValue()).isEqualByComparingTo("45.00");
  }

  @Test
  @DisplayName("create deve vincular nota ao produtor autenticado e retornar produtorId correto")
  void createShouldLinkNotaToAuthenticatedProdutor() {
    when(producerProvider.get()).thenReturn(producer);
    when(invoiceRepository.save(any(Invoice.class))).thenAnswer(inv -> inv.getArgument(0));

    InvoiceResponse response = invoiceService.create(buildRequest());

    assertThat(response.producerId()).isEqualTo(producerId);
  }

  @Test
  @DisplayName("create deve fixar modelo '55' e status RASCUNHO independente do request")
  void createShouldFixModeloAs55AndStatusAsRascunho() {
    when(producerProvider.get()).thenReturn(producer);
    when(invoiceRepository.save(any(Invoice.class))).thenAnswer(inv -> inv.getArgument(0));

    InvoiceResponse response = invoiceService.create(buildRequest());

    assertThat(response.model()).isEqualTo("55");
    assertThat(response.status()).isEqualTo(InvoiceStatus.RASCUNHO);
  }

  @Test
  @DisplayName("create deve usar serie 1 como padrao quando serie nao informada no request")
  void createShouldDefaultSerieToOneWhenNull() {
    InvoiceRequest requestSemSerie = new InvoiceRequest(
        1L, null, "Venda de producao rural",
        OffsetDateTime.now(), null,
        "Comprador Teste", "12345678901",
        null, null,
        List.of(new InvoiceItemRequest(
            1, null, "Soja", "12010090", "6101", "KG",
            new BigDecimal("1"), new BigDecimal("10.00"), null, null, null)),
        null);

    when(producerProvider.get()).thenReturn(producer);
    when(invoiceRepository.save(any(Invoice.class))).thenAnswer(inv -> inv.getArgument(0));

    InvoiceResponse response = invoiceService.create(requestSemSerie);

    assertThat(response.series()).isEqualTo(1);
  }

  // -------------------------------------------------------------------------
  // findAll — paginacao repassada ao repository
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("findAll deve repassar exatamente o Pageable recebido ao repository")
  void findAllShouldForwardPageableToRepository() {
    var pageable = PageRequest.of(2, 10);
    Page<Invoice> emptyPage = new PageImpl<>(List.of(), pageable, 0);

    when(producerProvider.get()).thenReturn(producer);
    when(invoiceRepository.findByProducerId(producerId, pageable)).thenReturn(emptyPage);

    Page<InvoiceResponse> result = invoiceService.findAll(pageable);

    assertThat(result.getPageable().getPageNumber()).isEqualTo(2);
    assertThat(result.getPageable().getPageSize()).isEqualTo(10);
    verify(invoiceRepository).findByProducerId(producerId, pageable);
  }

  // -------------------------------------------------------------------------
  // update — invoice nao encontrada deve lancar ResourceNotFoundException
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("update deve lancar ResourceNotFoundException quando nota nao encontrada")
  void updateShouldThrowResourceNotFoundWhenNotaNotFound() {
    UUID invoiceId = UUID.randomUUID();

    when(producerProvider.get()).thenReturn(producer);
    when(invoiceRepository.findByIdAndProducerId(invoiceId, producerId))
        .thenReturn(Optional.empty());

    InvoiceRequest request = buildRequest();

    assertThatThrownBy(() -> invoiceService.update(invoiceId, request))
        .isInstanceOf(ResourceNotFoundException.class)
        .hasMessageContaining(invoiceId.toString());

    verify(invoiceRepository, never()).save(any(Invoice.class));
  }

  @Test
  @DisplayName("update deve substituir todos os itens e retornar contagem correta apos update")
  void updateShouldReplaceAllItemsAndReturnCorrectCount() {
    UUID invoiceId = UUID.randomUUID();
    // Nota original tem 1 item
    Invoice notaExistente = buildNotaSalva();
    notaExistente.setId(invoiceId);
    assertThat(notaExistente.getItems()).hasSize(1);

    // Novo request com 2 items
    InvoiceItemRequest item1 = new InvoiceItemRequest(
        1, null, "Soja", "12010090", "6101", "KG",
        new BigDecimal("100"), new BigDecimal("3.00"), null, null, null);
    InvoiceItemRequest item2 = new InvoiceItemRequest(
        2, null, "Milho", "10059010", "6101", "KG",
        new BigDecimal("200"), new BigDecimal("1.50"), null, null, null);

    InvoiceRequest requestComDoisItens = new InvoiceRequest(
        1L, 1, "Venda de producao rural",
        OffsetDateTime.now(), null,
        "Jose Comprador", "12345678901",
        null, null,
        List.of(item1, item2), null);

    when(producerProvider.get()).thenReturn(producer);
    when(invoiceRepository.findByIdAndProducerId(invoiceId, producerId))
        .thenReturn(Optional.of(notaExistente));
    when(invoiceRepository.save(any(Invoice.class))).thenAnswer(inv -> inv.getArgument(0));

    InvoiceResponse response = invoiceService.update(invoiceId, requestComDoisItens);

    // 100*3.00 = 300.00, 200*1.50 = 300.00, total = 600.00
    assertThat(response.items()).hasSize(2);
    assertThat(response.totalProductsValue()).isEqualByComparingTo("600.00");
    assertThat(response.totalInvoiceValue()).isEqualByComparingTo("600.00");
  }

  // -------------------------------------------------------------------------
  // delete — invoice nao encontrada deve lancar ResourceNotFoundException
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("delete deve lancar ResourceNotFoundException quando nota nao encontrada")
  void deleteShouldThrowResourceNotFoundWhenNotaNotFound() {
    UUID invoiceId = UUID.randomUUID();

    when(producerProvider.get()).thenReturn(producer);
    when(invoiceRepository.findByIdAndProducerId(invoiceId, producerId))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> invoiceService.delete(invoiceId))
        .isInstanceOf(ResourceNotFoundException.class)
        .hasMessageContaining(invoiceId.toString());

    verify(invoiceRepository, never()).delete(any(Invoice.class));
  }

  // -------------------------------------------------------------------------
  // generateXml — happy path, idempotencia e ownership
  // -------------------------------------------------------------------------

  /**
   * XML de exemplo com key de acesso para uso nos testes de generateXml.
   * Formato correto: Id="NFe" + 44 digitos numericos.
   * Composicao: cUF(2) + AAMM(4) + CNPJ(14) + mod(2) + series(3) + nNF(9) + tpEmis(1) + cNF(8) + cDV(1) = 44
   */
  private static final String CHAVE_FAKE = "41262612345678000195550010000000011000000012";
  // 44 digitos: 41 (PR) + 2626 (AAMM) + 12345678000195 (CNPJ) + 55 (mod) + 001 + 000000001 + 1 + 00000001 + 2
  private static final String XML_FAKE =
      "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
          + "<NFe xmlns=\"http://www.portalfiscal.inf.br/nfe\">"
          + "<infNFe Id=\"NFe" + CHAVE_FAKE + "\" versao=\"4.00\"></infNFe></NFe>";

  @Test
  @DisplayName("gerarXml deve persistir xml, status XML_GERADO, versao e chave de acesso")
  void gerarXmlShouldPersistXmlAndTransitionStatus() {
    UUID invoiceId = UUID.randomUUID();
    Invoice invoice = buildNotaSalva();
    invoice.setId(invoiceId);

    when(producerProvider.get()).thenReturn(producer);
    when(invoiceRepository.findByIdAndProducerId(invoiceId, producerId))
        .thenReturn(Optional.of(invoice));
    when(invoiceXmlService.generateXml(invoice)).thenReturn(XML_FAKE);
    when(invoiceRepository.save(any(Invoice.class))).thenAnswer(inv -> inv.getArgument(0));

    InvoiceResponse response = invoiceService.generateXml(invoiceId);

    assertThat(response.status()).isEqualTo(InvoiceStatus.XML_GERADO);
    assertThat(response.accessKey()).isEqualTo(CHAVE_FAKE);
    // Verifica persistencia via save
    verify(invoiceRepository).save(any(Invoice.class));
    // Verifica que o XML e a versao foram atribuidos a entidade
    assertThat(invoice.getGeneratedXml()).isEqualTo(XML_FAKE);
    assertThat(invoice.getNfeLayoutVersion()).isEqualTo("4.00");
    assertThat(invoice.getXmlGenerationDate()).isNotNull();
    assertThat(invoice.getAccessKey()).isEqualTo(CHAVE_FAKE);
  }

  @Test
  @DisplayName("gerarXml deve lancar NotaFiscalNaoEditavelException quando status nao e RASCUNHO")
  void gerarXmlShouldThrowWhenStatusIsNotRascunho() {
    UUID invoiceId = UUID.randomUUID();
    Invoice invoice = buildNotaSalva();
    invoice.setId(invoiceId);
    invoice.setStatus(InvoiceStatus.XML_GERADO);

    when(producerProvider.get()).thenReturn(producer);
    when(invoiceRepository.findByIdAndProducerId(invoiceId, producerId))
        .thenReturn(Optional.of(invoice));

    assertThatThrownBy(() -> invoiceService.generateXml(invoiceId))
        .isInstanceOf(InvoiceNotEditableException.class)
        .hasMessageContaining(invoiceId.toString());

    verify(invoiceRepository, never()).save(any(Invoice.class));
  }

  @Test
  @DisplayName("gerarXml deve lancar ResourceNotFoundException quando nota pertence a outro produtor")
  void gerarXmlShouldThrowWhenNotaBelongsToAnotherProdutor() {
    UUID invoiceId = UUID.randomUUID();

    when(producerProvider.get()).thenReturn(producer);
    when(invoiceRepository.findByIdAndProducerId(invoiceId, producerId))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> invoiceService.generateXml(invoiceId))
        .isInstanceOf(ResourceNotFoundException.class)
        .hasMessageContaining(invoiceId.toString());

    verify(invoiceRepository, never()).save(any(Invoice.class));
  }

  @Test
  @DisplayName("gerarXml deve lancar XmlGenerationException e NAO persistir se a chave nao for extraivel")
  void gerarXmlShouldThrowAndNotPersistWhenChaveCannotBeExtracted() {
    UUID invoiceId = UUID.randomUUID();
    Invoice invoice = buildNotaSalva();
    invoice.setId(invoiceId);

    // XML sem o atributo Id="NFe..." → key nao pode ser extraida
    String xmlSemChave =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<NFe xmlns=\"http://www.portalfiscal.inf.br/nfe\"><infNFe versao=\"4.00\"></infNFe></NFe>";

    when(producerProvider.get()).thenReturn(producer);
    when(invoiceRepository.findByIdAndProducerId(invoiceId, producerId))
        .thenReturn(Optional.of(invoice));
    when(invoiceXmlService.generateXml(invoice)).thenReturn(xmlSemChave);

    assertThatThrownBy(() -> invoiceService.generateXml(invoiceId))
        .isInstanceOf(br.com.agrocontabil.exception.XmlGenerationException.class);

    // Nao deve persistir state inconsistente (key nula)
    verify(invoiceRepository, never()).save(any(Invoice.class));
  }

  // -------------------------------------------------------------------------
  // generateXml — registro de inconsistencias e status COM_INCONSISTENCIAS (issue #22)
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("gerarXml deve registrar inconsistencias e relancar 422 quando a validacao fiscal falha")
  void gerarXmlShouldRegisterInconsistenciasAndRethrowWhenValidationFails() {
    UUID invoiceId = UUID.randomUUID();
    Invoice invoice = buildNotaSalva();
    invoice.setId(invoiceId);

    var violation = new br.com.agrocontabil.exception
        .InvoiceInvalidForIssuanceException.FieldViolation(
        "item[1].ncm", "NCM e obrigatorio no layout da NF-e");
    var emissaoException = new br.com.agrocontabil.exception
        .InvoiceInvalidForIssuanceException(List.of(violation));

    when(producerProvider.get()).thenReturn(producer);
    when(invoiceRepository.findByIdAndProducerId(invoiceId, producerId))
        .thenReturn(Optional.of(invoice));
    when(invoiceXmlService.generateXml(invoice)).thenThrow(emissaoException);

    assertThatThrownBy(() -> invoiceService.generateXml(invoiceId))
        .isInstanceOf(br.com.agrocontabil.exception.InvoiceInvalidForIssuanceException.class);

    // Inconsistencias persistidas com os ErrorDetail da excecao; invoice nao transita para XML_GERADO
    verify(validationService).recordInconsistencies(eq(invoiceId), eq(emissaoException.getErrors()));
    verify(invoiceRepository, never()).save(any(Invoice.class));
  }

  @Test
  @DisplayName("gerarXml deve registrar inconsistencias quando a validacao XSD falha (XmlGenerationException)")
  void gerarXmlShouldRegisterInconsistenciasWhenXsdValidationFails() {
    UUID invoiceId = UUID.randomUUID();
    Invoice invoice = buildNotaSalva();
    invoice.setId(invoiceId);

    var xmlException = new br.com.agrocontabil.exception.XmlGenerationException(
        "XML invalido segundo o XSD");

    when(producerProvider.get()).thenReturn(producer);
    when(invoiceRepository.findByIdAndProducerId(invoiceId, producerId))
        .thenReturn(Optional.of(invoice));
    when(invoiceXmlService.generateXml(invoice)).thenThrow(xmlException);

    assertThatThrownBy(() -> invoiceService.generateXml(invoiceId))
        .isInstanceOf(br.com.agrocontabil.exception.XmlGenerationException.class);

    verify(validationService).recordInconsistencies(eq(invoiceId), eq(xmlException.getErrors()));
    verify(invoiceRepository, never()).save(any(Invoice.class));
  }

  @Test
  @DisplayName("gerarXml deve ser permitido a partir de COM_INCONSISTENCIAS (nota corrigida e reprocessada)")
  void gerarXmlShouldBeAllowedFromComInconsistencias() {
    UUID invoiceId = UUID.randomUUID();
    Invoice invoice = buildNotaSalva();
    invoice.setId(invoiceId);
    invoice.setStatus(InvoiceStatus.COM_INCONSISTENCIAS);

    when(producerProvider.get()).thenReturn(producer);
    when(invoiceRepository.findByIdAndProducerId(invoiceId, producerId))
        .thenReturn(Optional.of(invoice));
    when(invoiceXmlService.generateXml(invoice)).thenReturn(XML_FAKE);
    when(invoiceRepository.save(any(Invoice.class))).thenAnswer(inv -> inv.getArgument(0));

    InvoiceResponse response = invoiceService.generateXml(invoiceId);

    assertThat(response.status()).isEqualTo(InvoiceStatus.XML_GERADO);
    assertThat(response.accessKey()).isEqualTo(CHAVE_FAKE);
  }

  @Test
  @DisplayName("update deve ser permitido a partir de COM_INCONSISTENCIAS (correcao dos campos)")
  void updateShouldBeAllowedFromComInconsistencias() {
    UUID invoiceId = UUID.randomUUID();
    Invoice invoice = buildNotaSalva();
    invoice.setId(invoiceId);
    invoice.setStatus(InvoiceStatus.COM_INCONSISTENCIAS);

    when(producerProvider.get()).thenReturn(producer);
    when(invoiceRepository.findByIdAndProducerId(invoiceId, producerId))
        .thenReturn(Optional.of(invoice));
    when(invoiceRepository.save(any(Invoice.class))).thenAnswer(inv -> inv.getArgument(0));

    InvoiceResponse response = invoiceService.update(invoiceId, buildRequest());

    assertThat(response).isNotNull();
    verify(invoiceRepository).save(any(Invoice.class));
  }

  // -------------------------------------------------------------------------
  // getXml — happy path e nao gerado
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("obterXml deve retornar o XML armazenado quando existente")
  void obterXmlShouldReturnStoredXml() {
    UUID invoiceId = UUID.randomUUID();
    Invoice invoice = buildNotaSalva();
    invoice.setId(invoiceId);
    invoice.setStatus(InvoiceStatus.XML_GERADO);
    invoice.setGeneratedXml(XML_FAKE);

    when(producerProvider.get()).thenReturn(producer);
    when(invoiceRepository.findByIdAndProducerId(invoiceId, producerId))
        .thenReturn(Optional.of(invoice));

    String xml = invoiceService.getXml(invoiceId);

    assertThat(xml).isEqualTo(XML_FAKE);
  }

  @Test
  @DisplayName("obterXml deve lancar ResourceNotFoundException quando XML ainda nao foi gerado")
  void obterXmlShouldThrowWhenXmlNotYetGenerated() {
    UUID invoiceId = UUID.randomUUID();
    Invoice invoice = buildNotaSalva();
    invoice.setId(invoiceId);
    // generatedXml = null (status RASCUNHO, XML nao gerado)

    when(producerProvider.get()).thenReturn(producer);
    when(invoiceRepository.findByIdAndProducerId(invoiceId, producerId))
        .thenReturn(Optional.of(invoice));

    assertThatThrownBy(() -> invoiceService.getXml(invoiceId))
        .isInstanceOf(ResourceNotFoundException.class)
        .hasMessageContaining(invoiceId.toString());
  }

  // -------------------------------------------------------------------------
  // generateXml — roteamento por documentType
  // -------------------------------------------------------------------------

  /** XML minimo com atributo Id no formato exigido por extractAccessKeyFromXml. */
  private static final String VALID_XML =
      "<?xml version=\"1.0\"?><NFe><infNFe Id=\"NFe"
          + "41262612345678000195550010000000011000000012\"></infNFe></NFe>";

  @Test
  @DisplayName("gerarXml roteia para o pipeline NF-e quando tipoDocumento e NFE")
  void gerarXmlRoteiaParaNfe() {
    UUID invoiceId = UUID.randomUUID();
    Invoice invoice = Invoice.builder()
        .id(invoiceId)
        .number(1L)
        .documentType(DocumentType.NFE)
        .status(InvoiceStatus.RASCUNHO)
        .producer(producer)
        .build();

    when(producerProvider.get()).thenReturn(producer);
    when(invoiceRepository.findByIdAndProducerId(invoiceId, producerId))
        .thenReturn(Optional.of(invoice));
    when(invoiceXmlService.generateXml(invoice)).thenReturn(VALID_XML);
    when(invoiceRepository.save(any(Invoice.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    invoiceService.generateXml(invoiceId);

    verify(invoiceXmlService).generateXml(invoice);
    verify(nfaXmlService, never()).generateXml(any());
  }

  @Test
  @DisplayName("gerarXml roteia para o pipeline NFA quando tipoDocumento e NFA")
  void gerarXmlRoteiaParaNfa() {
    UUID invoiceId = UUID.randomUUID();
    Invoice invoice = Invoice.builder()
        .id(invoiceId)
        .number(1L)
        .documentType(DocumentType.NFA)
        .status(InvoiceStatus.RASCUNHO)
        .producer(producer)
        .build();

    when(producerProvider.get()).thenReturn(producer);
    when(invoiceRepository.findByIdAndProducerId(invoiceId, producerId))
        .thenReturn(Optional.of(invoice));
    when(nfaXmlService.generateXml(invoice)).thenReturn(VALID_XML);
    when(invoiceRepository.save(any(Invoice.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    invoiceService.generateXml(invoiceId);

    verify(nfaXmlService).generateXml(invoice);
    verify(invoiceXmlService, never()).generateXml(any());
  }

  // -------------------------------------------------------------------------
  // cancel — RN006
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("cancelar dentro do prazo transiciona para CANCELADA e chama a SEFAZ")
  void cancelarDentroDoPrazo() {
    UUID invoiceId = UUID.randomUUID();
    Invoice invoice = Invoice.builder()
        .id(invoiceId)
        .number(1L)
        .status(InvoiceStatus.AUTORIZADA)
        .accessKey("41260612345678000195550010000000011000000017")
        .issuanceDate(OffsetDateTime.now().minusDays(5))
        .producer(producer)
        .build();

    when(producerProvider.get()).thenReturn(producer);
    when(invoiceRepository.findByIdAndProducerId(invoiceId, producerId))
        .thenReturn(Optional.of(invoice));
    when(sefazService.cancel(any(), any()))
        .thenReturn(new SefazCancellationResult("MOCK000000000000001", OffsetDateTime.now()));
    when(invoiceRepository.save(any(Invoice.class))).thenAnswer(inv -> inv.getArgument(0));

    invoiceService.cancel(invoiceId, new CancellationRequest("Erro nos dados do destinatario"));

    assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.CANCELADA);
    assertThat(invoice.getCancellationDate()).isNotNull();
    assertThat(invoice.getCancellationProtocol()).isEqualTo("MOCK000000000000001");
    assertThat(invoice.getCancellationReason()).isEqualTo("Erro nos dados do destinatario");
    verify(sefazService).cancel(eq("41260612345678000195550010000000011000000017"), any());
  }

  @Test
  @DisplayName("cancelar fora do prazo de 30 dias lanca BusinessException (422) sem chamar SEFAZ")
  void cancelarForaDoPrazo() {
    UUID invoiceId = UUID.randomUUID();
    Invoice invoice = Invoice.builder()
        .id(invoiceId).number(1L).status(InvoiceStatus.AUTORIZADA)
        .accessKey("41260612345678000195550010000000011000000017")
        .issuanceDate(OffsetDateTime.now().minusDays(31))
        .producer(producer)
        .build();

    when(producerProvider.get()).thenReturn(producer);
    when(invoiceRepository.findByIdAndProducerId(invoiceId, producerId))
        .thenReturn(Optional.of(invoice));

    assertThatThrownBy(() ->
        invoiceService.cancel(invoiceId, new CancellationRequest("Motivo com mais de 15 chars")))
        .isInstanceOf(BusinessException.class);

    verify(sefazService, never()).cancel(any(), any());
    verify(invoiceRepository, never()).save(any(Invoice.class));
  }

  @Test
  @DisplayName("cancelar nota nao-AUTORIZADA lanca NotaFiscalNaoEditavelException (409)")
  void cancelarStatusInvalido() {
    UUID invoiceId = UUID.randomUUID();
    Invoice invoice = Invoice.builder()
        .id(invoiceId).number(1L).status(InvoiceStatus.RASCUNHO)
        .issuanceDate(OffsetDateTime.now())
        .producer(producer)
        .build();

    when(producerProvider.get()).thenReturn(producer);
    when(invoiceRepository.findByIdAndProducerId(invoiceId, producerId))
        .thenReturn(Optional.of(invoice));

    assertThatThrownBy(() ->
        invoiceService.cancel(invoiceId, new CancellationRequest("Motivo com mais de 15 chars")))
        .isInstanceOf(InvoiceNotEditableException.class);

    verify(sefazService, never()).cancel(any(), any());
  }

  @Test
  @DisplayName("cancelar nota de outro produtor lanca ResourceNotFoundException (404)")
  void cancelarNotaInexistente() {
    UUID invoiceId = UUID.randomUUID();
    when(producerProvider.get()).thenReturn(producer);
    when(invoiceRepository.findByIdAndProducerId(invoiceId, producerId))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() ->
        invoiceService.cancel(invoiceId, new CancellationRequest("Motivo com mais de 15 chars")))
        .isInstanceOf(ResourceNotFoundException.class);
  }

  // -------------------------------------------------------------------------
  // generatePdf (issue #14 — DANFE)
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("gerarPdf deve retornar os bytes do PDF quando o XML ja foi gerado")
  void gerarPdfShouldReturnBytesWhenXmlExists() {
    UUID invoiceId = UUID.randomUUID();
    Invoice invoice = buildNotaSalva();
    invoice.setId(invoiceId);
    invoice.setStatus(InvoiceStatus.XML_GERADO);
    invoice.setGeneratedXml("<NFe/>");
    byte[] pdfFake = new byte[] {'%', 'P', 'D', 'F'};

    when(producerProvider.get()).thenReturn(producer);
    when(invoiceRepository.findByIdAndProducerId(invoiceId, producerId))
        .thenReturn(Optional.of(invoice));
    when(danfePdfService.generatePdf(invoice)).thenReturn(pdfFake);

    byte[] result = invoiceService.generatePdf(invoiceId);

    assertThat(result).isEqualTo(pdfFake);
  }

  @Test
  @DisplayName("gerarPdf deve lancar DanfeIndisponivelException quando o XML ainda nao foi gerado")
  void gerarPdfShouldThrowWhenXmlNull() {
    UUID invoiceId = UUID.randomUUID();
    Invoice invoice = buildNotaSalva(); // RASCUNHO, generatedXml == null
    invoice.setId(invoiceId);

    when(producerProvider.get()).thenReturn(producer);
    when(invoiceRepository.findByIdAndProducerId(invoiceId, producerId))
        .thenReturn(Optional.of(invoice));

    assertThatThrownBy(() -> invoiceService.generatePdf(invoiceId))
        .isInstanceOf(DanfeUnavailableException.class)
        .hasMessageContaining(invoiceId.toString());
  }

  @Test
  @DisplayName("gerarPdf deve lancar ResourceNotFoundException quando nota pertence a outro produtor")
  void gerarPdfShouldThrowWhenNotaOfAnotherProdutor() {
    UUID invoiceId = UUID.randomUUID();

    when(producerProvider.get()).thenReturn(producer);
    when(invoiceRepository.findByIdAndProducerId(invoiceId, producerId))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> invoiceService.generatePdf(invoiceId))
        .isInstanceOf(ResourceNotFoundException.class)
        .hasMessageContaining(invoiceId.toString());
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private InvoiceRequest buildRequest() {
    InvoiceItemRequest item = new InvoiceItemRequest(
        1,
        null,
        "Soja em graos",
        "12010090",
        "6101",
        "KG",
        new BigDecimal("500.0000"),
        new BigDecimal("2.0000"),
        null,   // cstIbsCbs  — opcional
        null,   // cClassTrib — opcional
        null);  // reductionPercentage — opcional (default 0)

    return new InvoiceRequest(
        1L,
        1,
        "Venda de producao rural",
        OffsetDateTime.now(),
        null,
        "Jose Comprador",
        "12345678901",
        null,
        null,
        List.of(item),
        null);
  }

  private Invoice buildNotaSalva() {
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
        .createdAt(OffsetDateTime.now())
        .updatedAt(OffsetDateTime.now())
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
        .recipientTaxId("12345678901")
        .recipientAddress(RecipientAddress.builder().build())
        .totalProductsValue(new BigDecimal("1000.00"))
        .totalInvoiceValue(new BigDecimal("1000.00"))
        .status(InvoiceStatus.RASCUNHO)
        .createdAt(OffsetDateTime.now())
        .updatedAt(OffsetDateTime.now())
        .build();

    invoice.addItem(item);
    return invoice;
  }
}
