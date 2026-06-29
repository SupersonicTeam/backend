package br.com.agrocontabil.mapper;

import br.com.agrocontabil.dto.InvoiceItemRequest;
import br.com.agrocontabil.dto.InvoiceItemResponse;
import br.com.agrocontabil.dto.InvoiceRequest;
import br.com.agrocontabil.dto.InvoiceResponse;
import br.com.agrocontabil.dto.InvoiceResponse.RecipientAddressResponse;
import br.com.agrocontabil.dto.InvoiceValidationResponse;
import br.com.agrocontabil.model.RecipientAddress;
import br.com.agrocontabil.model.Invoice;
import br.com.agrocontabil.model.InvoiceItem;
import br.com.agrocontabil.model.InvoiceValidation;
import br.com.agrocontabil.model.Producer;
import br.com.agrocontabil.model.InvoiceStatus;
import br.com.agrocontabil.model.DocumentType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Converte entre DTOs de invoice fiscal e entidades JPA.
 *
 * <p>Regras de negócio do mapper:
 * <ul>
 *   <li>O model é fixado como {@code "55"} (NF-e) — nunca aceito do cliente.</li>
 *   <li>O status inicial é sempre {@link InvoiceStatus#RASCUNHO}.</li>
 *   <li>A série padrão é {@code 1} quando não informada.</li>
 *   <li>{@code totalValue} de cada item = {@code quantity * unitPrice},
 *       arredondado HALF_UP em 2 casas decimais.</li>
 *   <li>{@code totalProductsValue} = sum dos totalValue dos items.</li>
 *   <li>{@code totalInvoiceValue} = {@code totalProductsValue} (sem impostos na Sprint 2).</li>
 *   <li>Campos IBS/CBS dos items ({@code cstIbsCbs}, {@code cClassTrib},
 *       {@code reductionPercentage}) são copiados do request quando presentes;
 *       os valores calculados ({@code ibsValue}, {@code cbsValue} etc.) são preenchidos
 *       pelo {@link br.com.agrocontabil.service.IbsCbsCalculationService} — nunca aceitos
 *       do cliente (evita mass-assignment).</li>
 * </ul>
 *
 * <p>Esta classe não é um Spring bean — todos os métodos são estáticos.
 * Isso mantém o mapper testável sem contexto Spring.
 */
public final class InvoiceMapper {

  private InvoiceMapper() {}

  /**
   * Converte um {@link InvoiceRequest} em uma entidade {@link Invoice} pronta para
   * persistência.
   *
   * <p>O producer passado por parâmetro é o producer autenticado; nunca vem do corpo da
   * requisição.
   *
   * @param request  dados de entrada validados
   * @param producer producer autenticado que será o emissor da invoice
   * @return entidade montada com items, totais calculados e status RASCUNHO
   */
  public static Invoice toEntity(InvoiceRequest request, Producer producer) {
    Invoice invoice = Invoice.builder()
        .number(request.number())
        .series(request.series() != null ? request.series() : 1)
        .model("55")
        .documentType(request.documentType() != null
            ? request.documentType() : DocumentType.NFE)
        .operationNature(request.operationNature())
        .issuanceDate(request.issuanceDate())
        .exitDate(request.exitDate())
        .producer(producer)
        .recipientName(request.recipientName())
        .recipientTaxId(request.recipientTaxId())
        .recipientStateRegistration(request.recipientStateRegistration())
        .recipientAddress(toEnderecoEntity(request.recipientAddress()))
        .status(InvoiceStatus.RASCUNHO)
        .totalProductsValue(BigDecimal.ZERO)
        .totalInvoiceValue(BigDecimal.ZERO)
        .build();

    List<InvoiceItem> items = request.items().stream()
        .map(InvoiceMapper::toItemEntity)
        .toList();

    items.forEach(invoice::addItem);

    BigDecimal totalProducts = calculateTotalProducts(invoice.getItems());
    invoice.setTotalProductsValue(totalProducts);
    invoice.setTotalInvoiceValue(totalProducts);

    return invoice;
  }

  /**
   * Converte uma entidade {@link Invoice} em {@link InvoiceResponse}.
   *
   * @param invoice entidade persistida
   * @return DTO de saída com todos os campos, incluindo items e campos IBS/CBS mapeados
   */
  public static InvoiceResponse toResponse(Invoice invoice) {
    List<InvoiceItemResponse> itensResponse = invoice.getItems().stream()
        .map(InvoiceMapper::toItemResponse)
        .toList();

    return new InvoiceResponse(
        invoice.getId(),
        invoice.getAccessKey(),
        invoice.getNumber(),
        invoice.getSeries(),
        invoice.getModel(),
        invoice.getOperationNature(),
        invoice.getStatus(),
        invoice.getIssuanceDate(),
        invoice.getExitDate(),
        invoice.getProducer().getId(),
        invoice.getRecipientName(),
        invoice.getRecipientTaxId(),
        invoice.getRecipientStateRegistration(),
        toEnderecoResponse(invoice.getRecipientAddress()),
        invoice.getTotalProductsValue(),
        invoice.getTotalInvoiceValue(),
        invoice.getTotalIbsValue(),
        invoice.getTotalCbsValue(),
        itensResponse,
        invoice.getCreatedAt(),
        invoice.getUpdatedAt(),
        invoice.getDocumentType(),
        invoice.getCancellationDate(),
        invoice.getCancellationReason());
  }

  /**
   * Converte uma {@link InvoiceValidation} em {@link InvoiceValidationResponse} (issue #22).
   *
   * @param validacao inconsistência de validação persistida
   * @return DTO de saída para o histórico de validações
   */
  public static InvoiceValidationResponse toValidacaoResponse(InvoiceValidation validacao) {
    return new InvoiceValidationResponse(
        validacao.getValidationBatch(),
        validacao.getCode(),
        validacao.getField(),
        validacao.getMessage(),
        validacao.getSuggestion(),
        validacao.getRejectedValue(),
        validacao.getExpectedValue(),
        validacao.getValidatedAt());
  }

  /**
   * Converte uma lista de {@link InvoiceValidation} em DTOs de resposta, preservando a ordem
   * fornecida pela consulta (issue #22).
   *
   * @param validacoes inconsistências persistidas, já ordenadas pelo repositório
   * @return lista de DTOs de saída
   */
  public static List<InvoiceValidationResponse> toValidacaoResponseList(
      List<InvoiceValidation> validacoes) {
    return validacoes.stream()
        .map(InvoiceMapper::toValidacaoResponse)
        .toList();
  }

  // -------------------------------------------------------------------------
  // Helpers internos e de uso restrito ao pacote mapper
  // -------------------------------------------------------------------------

  /**
   * Converte uma lista de {@link InvoiceItemRequest} em entidades {@link InvoiceItem}.
   *
   * <p>Destinado ao uso pela camada de serviço quando é necessário substituir items
   * de uma invoice existente sem recriar a invoice inteira (ex.: operação de update).
   *
   * @param items lista de DTOs de entrada dos items, não nula
   * @return lista de entidades de items sem associação com invoice fiscal (sem ID)
   */
  public static List<InvoiceItem> toItemEntityList(List<InvoiceItemRequest> items) {
    return items.stream()
        .map(InvoiceMapper::toItemEntity)
        .toList();
  }

  /**
   * Converte um {@link InvoiceItemRequest} em entidade {@link InvoiceItem}.
   *
   * <p>Calcula {@code totalValue = quantity * unitPrice} com arredondamento HALF_UP
   * em 2 casas decimais. Copia os campos classificatórios opcionais de IBS/CBS
   * ({@code cstIbsCbs}, {@code cClassTrib}, {@code reductionPercentage}).
   * Os campos calculados (ibsValue, cbsValue etc.) são preenchidos posteriormente
   * pelo {@link br.com.agrocontabil.service.IbsCbsCalculationService}.
   *
   * @param req DTO de entrada do item
   * @return entidade do item sem associação com invoice fiscal (sem ID)
   */
  private static InvoiceItem toItemEntity(InvoiceItemRequest req) {
    BigDecimal totalValue = req.quantity()
        .multiply(req.unitPrice())
        .setScale(2, RoundingMode.HALF_UP);

    return InvoiceItem.builder()
        .itemNumber(req.itemNumber())
        .productCode(req.productCode())
        .description(req.description())
        .ncm(req.ncm())
        .cfop(req.cfop())
        .unit(req.unit())
        .quantity(req.quantity())
        .unitPrice(req.unitPrice())
        .totalValue(totalValue)
        .cstIbsCbs(req.cstIbsCbs())
        .cClassTrib(req.cClassTrib())
        .reductionPercentage(req.reductionPercentage())
        .build();
  }

  private static InvoiceItemResponse toItemResponse(InvoiceItem item) {
    return new InvoiceItemResponse(
        item.getId(),
        item.getItemNumber(),
        item.getProductCode(),
        item.getDescription(),
        item.getNcm(),
        item.getCfop(),
        item.getUnit(),
        item.getQuantity(),
        item.getUnitPrice(),
        item.getTotalValue(),
        item.getCstIbsCbs(),
        item.getCClassTrib(),
        item.getReductionPercentage(),
        item.getIbsCbsCalculationBase(),
        item.getIbsRate(),
        item.getIbsValue(),
        item.getCbsRate(),
        item.getCbsValue(),
        item.getCreatedAt(),
        item.getUpdatedAt());
  }

  private static RecipientAddress toEnderecoEntity(
      InvoiceRequest.RecipientAddressRequest req) {
    if (req == null) {
      return null;
    }
    return RecipientAddress.builder()
        .street(req.street())
        .number(req.number())
        .city(req.city())
        .ibgeCityCode(req.ibgeCityCode())
        .uf(req.uf())
        .zipCode(req.zipCode())
        .build();
  }

  private static RecipientAddressResponse toEnderecoResponse(RecipientAddress endereco) {
    if (endereco == null) {
      return null;
    }
    return new RecipientAddressResponse(
        endereco.getStreet(),
        endereco.getNumber(),
        endereco.getCity(),
        endereco.getIbgeCityCode(),
        endereco.getUf(),
        endereco.getZipCode());
  }

  private static BigDecimal calculateTotalProducts(List<InvoiceItem> items) {
    return items.stream()
        .map(InvoiceItem::getTotalValue)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }
}
