package br.com.agrocontabil.service;

import br.com.agrocontabil.danfe.DanfePdfService;
import br.com.agrocontabil.dto.CancellationRequest;
import br.com.agrocontabil.dto.InvoiceRequest;
import br.com.agrocontabil.dto.InvoiceResponse;
import br.com.agrocontabil.exception.BusinessException;
import br.com.agrocontabil.exception.DanfeUnavailableException;
import br.com.agrocontabil.exception.InvoiceInvalidForIssuanceException;
import br.com.agrocontabil.exception.InvoiceNotEditableException;
import br.com.agrocontabil.exception.ResourceNotFoundException;
import br.com.agrocontabil.exception.XmlGenerationException;
import br.com.agrocontabil.mapper.InvoiceMapper;
import br.com.agrocontabil.service.sefaz.SefazCancellationResult;
import br.com.agrocontabil.service.sefaz.SefazService;
import br.com.agrocontabil.model.Invoice;
import br.com.agrocontabil.model.InvoiceItem;
import br.com.agrocontabil.model.Producer;
import br.com.agrocontabil.model.InvoiceStatus;
import br.com.agrocontabil.model.DocumentType;
import br.com.agrocontabil.repository.InvoiceRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Servico de regras de negocio para {@link Invoice}.
 *
 * <p>Todas as operacoes verificam o producer autenticado via
 * {@link AuthenticatedProducerProvider}, garantindo que um producer nunca acesse
 * ou modifique notas de outro producer (anti-IDOR).
 *
 * <p>Regras de negocio:
 * <ul>
 *   <li>Criacao: status inicial RASCUNHO; totais calculados no mapper.</li>
 *   <li>Listagem: somente notas do producer autenticado, paginada.</li>
 *   <li>Busca por ID: 404 se inexistente OU se pertencer a outro producer.</li>
 *   <li>Atualizacao: somente se status == RASCUNHO; caso contrario, 409 Conflict.</li>
 *   <li>Exclusao: somente se status == RASCUNHO; caso contrario, 409 Conflict.</li>
 *   <li>Geracao de XML: somente se status == RASCUNHO; persiste XML, versao, data
 *       e transiciona status para XML_GERADO.</li>
 *   <li>Obtencao do XML: retorna o XML armazenado; 404 se ainda nao gerado.</li>
 * </ul>
 */
@Service
@Transactional
public class InvoiceService {

  /** Versao do layout NF-e gerado por esta implementacao. */
  private static final String NFE_LAYOUT_VERSION = "4.00";

  /** Limite legal para cancelamento de NF-e após a emissão (RN006). */
  private static final int CANCELLATION_DEADLINE_DAYS = 30;

  private static final Logger log = LoggerFactory.getLogger(InvoiceService.class);

  /**
   * Status em que a invoice ainda é considerada "rascunho" editável: além de
   * {@link InvoiceStatus#RASCUNHO}, uma invoice {@link InvoiceStatus#COM_INCONSISTENCIAS}
   * (issue #22) pode ser editada e ter o XML gerado novamente após a correção dos campos.
   */
  private static final Set<InvoiceStatus> STATUS_EDITAVEIS =
      EnumSet.of(InvoiceStatus.RASCUNHO, InvoiceStatus.COM_INCONSISTENCIAS);

  private final InvoiceRepository invoiceRepository;
  private final AuthenticatedProducerProvider producerProvider;
  private final IbsCbsCalculationService ibsCbsCalculationService;
  private final InvoiceXmlService invoiceXmlService;
  private final NfaXmlService nfaXmlService;
  private final CfopOperationValidator cfopOperationValidator;
  private final SefazService sefazService;
  private final DanfePdfService danfePdfService;
  private final InvoiceValidationService validationService;

  public InvoiceService(
      InvoiceRepository invoiceRepository,
      AuthenticatedProducerProvider producerProvider,
      IbsCbsCalculationService ibsCbsCalculationService,
      InvoiceXmlService invoiceXmlService,
      NfaXmlService nfaXmlService,
      CfopOperationValidator cfopOperationValidator,
      SefazService sefazService,
      DanfePdfService danfePdfService,
      InvoiceValidationService validationService) {
    this.invoiceRepository = invoiceRepository;
    this.producerProvider = producerProvider;
    this.ibsCbsCalculationService = ibsCbsCalculationService;
    this.invoiceXmlService = invoiceXmlService;
    this.nfaXmlService = nfaXmlService;
    this.cfopOperationValidator = cfopOperationValidator;
    this.sefazService = sefazService;
    this.danfePdfService = danfePdfService;
    this.validationService = validationService;
  }

  /**
   * Cria uma nova invoice fiscal vinculada ao producer autenticado.
   *
   * @param request dados validados da invoice fiscal
   * @return DTO de resposta com ID gerado e totais calculados
   */
  public InvoiceResponse create(InvoiceRequest request) {
    Producer producer = producerProvider.get();
    Invoice invoice = InvoiceMapper.toEntity(request, producer);
    // Calcula IBS/CBS após montagem dos items e cálculo dos totais de produtos.
    ibsCbsCalculationService.calculate(invoice);
    // Valida a compatibilidade CFOP × operação antes de persistir.
    cfopOperationValidator.validate(invoice);
    Invoice saved = invoiceRepository.save(invoice);
    log.info("NotaFiscal criada: id={} produtor={} numero={}",
        saved.getId(), producer.getId(), saved.getNumber());
    return InvoiceMapper.toResponse(saved);
  }

  /**
   * Lista todas as notas fiscais do producer autenticado, paginadas.
   *
   * @param pageable parametros de paginacao e ordenacao
   * @return pagina de notas do producer autenticado
   */
  @Transactional(readOnly = true)
  public Page<InvoiceResponse> findAll(Pageable pageable) {
    Producer producer = producerProvider.get();
    return invoiceRepository
        .findByProducerId(producer.getId(), pageable)
        .map(InvoiceMapper::toResponse);
  }

  /**
   * Busca uma invoice fiscal por ID, somente se pertencer ao producer autenticado.
   *
   * <p>Retorna 404 tanto se a invoice nao existir quanto se pertencer a outro producer
   * (anti-IDOR: nunca vazar 403 com informacoes de existencia do recurso).
   *
   * @param id identificador da invoice fiscal
   * @return DTO de resposta
   * @throws ResourceNotFoundException se nao encontrada ou se pertencer a outro producer
   */
  @Transactional(readOnly = true)
  public InvoiceResponse findById(UUID id) {
    Producer producer = producerProvider.get();
    Invoice invoice = invoiceRepository
        .findByIdAndProducerId(id, producer.getId())
        .orElseThrow(() -> new ResourceNotFoundException("NotaFiscal", id));
    return InvoiceMapper.toResponse(invoice);
  }

  /**
   * Atualiza uma invoice fiscal existente, somente se estiver em status RASCUNHO ou
   * {@link InvoiceStatus#COM_INCONSISTENCIAS} (issue #22 — invoice com inconsistencias
   * permanece corrigivel).
   *
   * <p>Os items antigos sao substituidos pelos novos (orphanRemoval cuida da exclusao
   * dos items removidos). Os totais sao recalculados.
   *
   * @param id      identificador da invoice fiscal
   * @param request novos dados validados
   * @return DTO de resposta atualizado
   * @throws ResourceNotFoundException        se nao encontrada ou pertencer a outro producer
   * @throws InvoiceNotEditableException   se o status nao for editavel (409 Conflict)
   */
  public InvoiceResponse update(UUID id, InvoiceRequest request) {
    Producer producer = producerProvider.get();
    Invoice invoice = invoiceRepository
        .findByIdAndProducerId(id, producer.getId())
        .orElseThrow(() -> new ResourceNotFoundException("NotaFiscal", id));

    if (!STATUS_EDITAVEIS.contains(invoice.getStatus())) {
      throw new InvoiceNotEditableException(id, invoice.getStatus());
    }

    invoice.setNumber(request.number());
    invoice.setSeries(request.series() != null ? request.series() : 1);
    invoice.setOperationNature(request.operationNature());
    invoice.setIssuanceDate(request.issuanceDate());
    invoice.setExitDate(request.exitDate());
    invoice.setRecipientName(request.recipientName());
    invoice.setRecipientTaxId(request.recipientTaxId());
    invoice.setRecipientStateRegistration(request.recipientStateRegistration());

    if (request.recipientAddress() != null) {
      invoice.setRecipientAddress(buildAddress(request.recipientAddress()));
    } else {
      invoice.setRecipientAddress(null);
    }

    // Substitui items — orphanRemoval remove os anteriores automaticamente
    List<InvoiceItem> newItems = InvoiceMapper.toItemEntityList(request.items());

    invoice.getItems().clear();
    newItems.forEach(invoice::addItem);

    BigDecimal totalProducts = invoice.getItems().stream()
        .map(InvoiceItem::getTotalValue)
        .reduce(BigDecimal.ZERO, BigDecimal::add);

    invoice.setTotalProductsValue(totalProducts);
    invoice.setTotalInvoiceValue(totalProducts);

    // Recalcula IBS/CBS após substituição dos items e recálculo dos totais de produtos.
    ibsCbsCalculationService.calculate(invoice);
    // Valida a compatibilidade CFOP × operação antes de persistir.
    cfopOperationValidator.validate(invoice);

    Invoice saved = invoiceRepository.save(invoice);
    log.info("NotaFiscal atualizada: id={} produtor={}", saved.getId(), producer.getId());
    return InvoiceMapper.toResponse(saved);
  }

  /**
   * Exclui uma invoice fiscal, somente se estiver em status RASCUNHO ou
   * {@link InvoiceStatus#COM_INCONSISTENCIAS} (issue #22).
   *
   * @param id identificador da invoice fiscal
   * @throws ResourceNotFoundException      se nao encontrada ou pertencer a outro producer
   * @throws InvoiceNotEditableException se o status nao for editavel (409 Conflict)
   */
  public void delete(UUID id) {
    Producer producer = producerProvider.get();
    Invoice invoice = invoiceRepository
        .findByIdAndProducerId(id, producer.getId())
        .orElseThrow(() -> new ResourceNotFoundException("NotaFiscal", id));

    if (!STATUS_EDITAVEIS.contains(invoice.getStatus())) {
      throw new InvoiceNotEditableException(id, invoice.getStatus());
    }

    invoiceRepository.delete(invoice);
    log.info("NotaFiscal excluida: id={} produtor={}", id, producer.getId());
  }

  // -------------------------------------------------------------------------
  // Geracao e obtencao do XML da NF-e (Sprint 2, Sessao 6)
  // -------------------------------------------------------------------------

  /**
   * Gera o XML da NF-e para a invoice indicada, persiste-o e transiciona o status.
   *
   * <p>Regras:
   * <ul>
   *   <li>Ownership: busca via {@code findByIdAndProducerId} — 404 se a invoice nao pertencer
   *       ao producer autenticado (anti-IDOR).</li>
   *   <li>Idempotencia/transicao: somente notas em {@link InvoiceStatus#RASCUNHO} ou
   *       {@link InvoiceStatus#COM_INCONSISTENCIAS} (issue #22) podem ter o XML gerado;
   *       qualquer outro status resulta em {@link InvoiceNotEditableException} (HTTP 409).</li>
   *   <li>Inconsistencias (issue #22): se a validacao fiscal ou XSD falhar, as inconsistencias
   *       sao persistidas, a invoice e marcada como {@link InvoiceStatus#COM_INCONSISTENCIAS}
   *       e o error 422 e propagado ao cliente. O historico fica em
   *       {@code GET /api/notas/{id}/validacoes}.</li>
   *   <li>Apos a geracao: {@code generatedXml}, {@code nfeLayoutVersion} e
   *       {@code xmlGenerationDate} sao preenchidos; o status transita para
   *       {@link InvoiceStatus#XML_GERADO}; a key de acesso e extraida do atributo
   *       {@code Id} da {@code infNFe} ({@code "NFe" + 44 digitos}) via
   *       {@link InvoiceXmlService#generateXml(Invoice)}.</li>
   *   <li>Imutabilidade: apos a transicao o status nao e RASCUNHO, portanto os endpoints
   *       de update e delete ja bloqueiam sobrescrita — o XML e a key sao imutaveis.</li>
   * </ul>
   *
   * @param id identificador da invoice fiscal
   * @return DTO de resposta atualizado com status XML_GERADO e key de acesso preenchida
   * @throws ResourceNotFoundException        se a invoice nao existir ou pertencer a outro producer
   * @throws InvoiceNotEditableException   se o status nao for RASCUNHO (409 Conflict)
   * @throws br.com.agrocontabil.exception.InvoiceInvalidForIssuanceException
   *         se dados obrigatorios da NF-e estiverem ausentes (422)
   * @throws br.com.agrocontabil.exception.XmlGenerationException
   *         se o XML gerado nao for valido segundo o XSD (422)
   */
  public InvoiceResponse generateXml(UUID id) {
    Producer producer = producerProvider.get();
    Invoice invoice = invoiceRepository
        .findByIdAndProducerId(id, producer.getId())
        .orElseThrow(() -> new ResourceNotFoundException("NotaFiscal", id));

    if (!STATUS_EDITAVEIS.contains(invoice.getStatus())) {
      throw new InvoiceNotEditableException(id, invoice.getStatus());
    }

    String xml = generateXmlRecordingInconsistencies(invoice);

    // Extrai a key de acesso do atributo Id da infNFe: "NFe" + 44 digitos numericos.
    // O InvoiceXmlService garante que o Id esta no formato correto apos geracao
    // bem-sucedida — fazemos a extracao direto do XML para nao expor o grafo JAXB.
    String accessKey = extractAccessKeyFromXml(xml);

    invoice.setGeneratedXml(xml);
    invoice.setNfeLayoutVersion(NFE_LAYOUT_VERSION);
    invoice.setXmlGenerationDate(OffsetDateTime.now());
    invoice.setStatus(InvoiceStatus.XML_GERADO);
    invoice.setAccessKey(accessKey);

    Invoice saved = invoiceRepository.save(invoice);
    log.info("XML da NF-e gerado e persistido: id={} produtor={} chave={}",
        saved.getId(), producer.getId(), accessKey);
    return InvoiceMapper.toResponse(saved);
  }

  /**
   * Retorna o XML da NF-e armazenado para a invoice indicada.
   *
   * <p>Ownership: busca via {@code findByIdAndProducerId} — 404 se a invoice nao pertencer
   * ao producer autenticado (anti-IDOR).
   *
   * @param id identificador da invoice fiscal
   * @return XML da NF-e como string UTF-8
   * @throws ResourceNotFoundException se a invoice nao existir, pertencer a outro producer
   *                                   ou o XML ainda nao tiver sido gerado
   */
  @Transactional(readOnly = true)
  public String getXml(UUID id) {
    Producer producer = producerProvider.get();
    Invoice invoice = invoiceRepository
        .findByIdAndProducerId(id, producer.getId())
        .orElseThrow(() -> new ResourceNotFoundException("NotaFiscal", id));

    if (invoice.getGeneratedXml() == null) {
      throw new ResourceNotFoundException(
          "XML da NF-e ainda nao gerado para a nota '" + id + "'. "
              + "Chame POST /api/notas/{id}/gerar-xml primeiro.");
    }

    return invoice.getGeneratedXml();
  }

  /**
   * Gera o PDF da DANFE da invoice indicada (issue #14 — RF011).
   *
   * <p>Ownership anti-IDOR: busca via {@code findByIdAndProducerId} — 404 se a invoice não
   * pertencer ao producer autenticado. A DANFE só está disponível após a geração do XML
   * (status XML_GERADO em diante); caso contrário, lança {@link DanfeUnavailableException}
   * (HTTP 409).
   *
   * @param id identificador da invoice fiscal
   * @return bytes do PDF da DANFE
   * @throws ResourceNotFoundException     se a invoice não existir ou pertencer a outro producer
   * @throws DanfeUnavailableException    se o XML ainda não foi gerado (HTTP 409)
   * @throws br.com.agrocontabil.exception.DanfeGenerationException
   *         se a geração do PDF falhar (HTTP 500)
   */
  @Transactional(readOnly = true)
  public byte[] generatePdf(UUID id) {
    Producer producer = producerProvider.get();
    Invoice invoice = invoiceRepository
        .findByIdAndProducerId(id, producer.getId())
        .orElseThrow(() -> new ResourceNotFoundException("NotaFiscal", id));

    if (invoice.getGeneratedXml() == null) {
      throw new DanfeUnavailableException(id, invoice.getStatus());
    }

    return danfePdfService.generatePdf(invoice);
  }

  // -------------------------------------------------------------------------
  // Helper privado de geracao de XML com registro de inconsistencias (issue #22)
  // -------------------------------------------------------------------------

  /**
   * Executa o pipeline de geração do XML, persistindo o histórico de inconsistências antes
   * de propagar o error ao cliente (issue #22).
   *
   * <p>Quando a validação fiscal ({@link InvoiceInvalidForIssuanceException}) ou a
   * validação do XML gerado ({@link XmlGenerationException}) falha, as inconsistências são
   * registradas via {@link InvoiceValidationService#recordInconsistencies(UUID, List)} —
   * numa transação independente, que sobrevive ao rollback desta — e a invoice é marcada como
   * {@link InvoiceStatus#COM_INCONSISTENCIAS}. A exceção é então relançada para que o
   * cliente continue recebendo o HTTP 422 com o envelope {@code errors[]}.
   *
   * @param invoice invoice fiscal a ter o XML gerado
   * @return XML gerado, assinado e validado em caso de success
   */
  private String generateXmlRecordingInconsistencies(Invoice invoice) {
    try {
      return invoice.getDocumentType() == DocumentType.NFA
          ? nfaXmlService.generateXml(invoice)
          : invoiceXmlService.generateXml(invoice);
    } catch (InvoiceInvalidForIssuanceException | XmlGenerationException e) {
      validationService.recordInconsistencies(invoice.getId(), e.getErrors());
      throw e;
    }
  }

  // -------------------------------------------------------------------------
  // Cancelamento (RN006) — Sprint 3
  // -------------------------------------------------------------------------

  /**
   * Cancela uma NF-e autorizada do producer autenticado, respeitando o limite de
   * {@value #CANCELLATION_DEADLINE_DAYS} dias após a emissão (RN006).
   *
   * @param id      identificador da invoice
   * @param request justificativa do cancelamento (validada no DTO)
   * @return DTO atualizado com status CANCELADA
   * @throws ResourceNotFoundException      se não existir ou for de outro producer (404)
   * @throws InvoiceNotEditableException se o status não for AUTORIZADA (409)
   * @throws BusinessException              se fora do prazo de 30 dias (422)
   */
  public InvoiceResponse cancel(UUID id, CancellationRequest request) {
    Producer producer = producerProvider.get();
    Invoice invoice = invoiceRepository
        .findByIdAndProducerId(id, producer.getId())
        .orElseThrow(() -> new ResourceNotFoundException("NotaFiscal", id));

    if (invoice.getStatus() != InvoiceStatus.AUTORIZADA) {
      throw InvoiceNotEditableException.naoCancelavel(id, invoice.getStatus());
    }

    long daysSinceIssuance = java.time.Duration
        .between(invoice.getIssuanceDate(), OffsetDateTime.now()).toDays();
    if (daysSinceIssuance > CANCELLATION_DEADLINE_DAYS) {
      throw new BusinessException(
          "Cancelamento nao permitido: a nota foi emitida ha " + daysSinceIssuance
              + " dias, acima do limite de " + CANCELLATION_DEADLINE_DAYS
              + " dias apos a emissao (RN006).");
    }

    SefazCancellationResult result =
        sefazService.cancel(invoice.getAccessKey(), request.reason());

    invoice.setStatus(InvoiceStatus.CANCELADA);
    invoice.setCancellationDate(result.dateTime());
    invoice.setCancellationReason(request.reason());
    invoice.setCancellationProtocol(result.protocol());

    Invoice saved = invoiceRepository.save(invoice);
    log.info("NotaFiscal cancelada: id={} produtor={} protocolo={}",
        saved.getId(), producer.getId(), result.protocol());
    return InvoiceMapper.toResponse(saved);
  }

  // -------------------------------------------------------------------------
  // Helper privado de extracao da key de acesso
  // -------------------------------------------------------------------------

  /**
   * Extrai os 44 digitos da key de acesso do atributo {@code Id} da infNFe no XML.
   *
   * <p>O formato e {@code Id="NFe&lt;44 digitos&gt;"} — remove o prefix "NFe" e retorna
   * apenas os 44 digitos numericos. Nao faz parse completo do XML para evitar overhead;
   * usa busca por substring confiavel dado que o InvoiceXmlService garante o formato.
   *
   * @param xml XML valido da NF-e
   * @return key de acesso com 44 digitos numericos
   * @throws XmlGenerationException se a key nao puder ser extraida (XML sem o atributo
   *         {@code Id} no formato esperado) — evita persistir o XML com key nula
   */
  private String extractAccessKeyFromXml(String xml) {
    // Formato: Id="NFe<44 digitos>"
    String prefix = "Id=\"NFe";
    int start = xml.indexOf(prefix);
    if (start == -1) {
      throw new XmlGenerationException(
          "Falha ao extrair a chave de acesso: atributo Id da infNFe nao encontrado no XML gerado.");
    }
    int chaveStart = start + prefix.length();
    // Chave tem exatamente 44 digitos
    if (chaveStart + 44 > xml.length()) {
      throw new XmlGenerationException(
          "Falha ao extrair a chave de acesso: XML truncado ou malformado.");
    }
    String key = xml.substring(chaveStart, chaveStart + 44);
    if (!key.matches("\\d{44}")) {
      throw new XmlGenerationException(
          "Falha ao extrair a chave de acesso: valor extraido nao possui 44 digitos numericos.");
    }
    return key;
  }

  // -------------------------------------------------------------------------
  // Helper privado de endereco (evita duplicar logica inline no update)
  // -------------------------------------------------------------------------

  /**
   * Converte o DTO de endereco do destinatario em um embedded {@code RecipientAddress}.
   *
   * <p>Centraliza a construcao do endereco para evitar duplicacao de logica entre
   * {@link #update(UUID, InvoiceRequest)} e quaisquer futuros metodos que precisem
   * instanciar o endereco a partir do request.
   *
   * @param req DTO de endereco validado pelo Bean Validation
   * @return entidade embutida pronta para ser associada a uma {@code Invoice}
   */
  private br.com.agrocontabil.model.RecipientAddress buildAddress(
      InvoiceRequest.RecipientAddressRequest req) {
    return br.com.agrocontabil.model.RecipientAddress.builder()
        .street(req.street())
        .number(req.number())
        .city(req.city())
        .ibgeCityCode(req.ibgeCityCode())
        .uf(req.uf())
        .zipCode(req.zipCode())
        .build();
  }
}
