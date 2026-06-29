package br.com.agrocontabil.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import br.com.agrocontabil.model.InvoiceStatus;
import br.com.agrocontabil.model.DocumentType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * DTO de saída para uma invoice fiscal.
 *
 * <p>Expõe todos os campos relevantes da entidade {@link br.com.agrocontabil.model.Invoice},
 * incluindo os valores calculados, status, items e timestamps de auditoria.
 * A entidade JPA nunca é exposta diretamente — sempre via este record.
 *
 * <p>Os campos {@code totalIbsValue} e {@code totalCbsValue} são nulos para notas emitidas
 * antes da Sprint 2 – Sessão 4 (IBS/CBS). Base legal: EC 132/2023 + LC 214/2025.
 */
@Schema(description = "Dados completos de uma nota fiscal eletronica (NF-e modelo 55)")
public record InvoiceResponse(

    @Schema(description = "Identificador unico da nota fiscal",
        example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
    UUID id,

    @Schema(description = "Chave de acesso NF-e (44 digitos; nula quando em RASCUNHO)",
        example = "35260612345678000195550010000000011234567890")
    @JsonProperty("chaveAcesso") String accessKey,

    @Schema(description = "Numero sequencial da NF-e", example = "1")
    @JsonProperty("numero") Long number,

    @Schema(description = "Serie da NF-e", example = "1")
    @JsonProperty("serie") Integer series,

    @Schema(description = "Modelo do documento fiscal", example = "55")
    @JsonProperty("modelo") String model,

    @Schema(description = "Natureza da operacao", example = "Venda de producao rural")
    @JsonProperty("naturezaOperacao") String operationNature,

    @Schema(description = "Status do ciclo de vida da nota", example = "RASCUNHO")
    InvoiceStatus status,

    @Schema(description = "Data e hora de emissao", example = "2026-06-10T10:00:00-03:00")
    @JsonProperty("dataEmissao") OffsetDateTime issuanceDate,

    @Schema(description = "Data e hora de saida/entrega (pode ser nula)",
        example = "2026-06-10T14:00:00-03:00")
    @JsonProperty("dataSaida") OffsetDateTime exitDate,

    @Schema(description = "Identificador do produtor emissor",
        example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
    @JsonProperty("produtorId") UUID producerId,

    @Schema(description = "Nome ou razao social do destinatario", example = "Jose Comprador")
    @JsonProperty("destinatarioNome") String recipientName,

    @Schema(description = "CPF ou CNPJ do destinatario (somente digitos)", example = "12345678901")
    @JsonProperty("destinatarioCpfCnpj") String recipientTaxId,

    @Schema(description = "Inscricao Estadual do destinatario (pode ser nula)")
    @JsonProperty("destinatarioIe") String recipientStateRegistration,

    @Schema(description = "Endereco do destinatario (pode ser nulo)")
    @JsonProperty("destinatarioEndereco") RecipientAddressResponse recipientAddress,

    @Schema(description = "Somatório dos valores dos produtos (sem impostos)", example = "1250.00")
    @JsonProperty("valorTotalProdutos") BigDecimal totalProductsValue,

    @Schema(description = "Valor total da nota fiscal", example = "1250.00")
    @JsonProperty("valorTotalNota") BigDecimal totalInvoiceValue,

    @Schema(
        description =
            "Somatório do IBS calculado de todos os itens. "
                + "Informativo em 2026 (ano-teste, LC 214/2025). "
                + "Nulo para notas anteriores à Sprint 2 Sessão 4.",
        example = "1.25")
    @JsonProperty("valorTotalIbs") BigDecimal totalIbsValue,

    @Schema(
        description =
            "Somatório da CBS calculada de todos os itens. "
                + "Informativo em 2026 (ano-teste, LC 214/2025). "
                + "Nulo para notas anteriores à Sprint 2 Sessão 4.",
        example = "11.25")
    @JsonProperty("valorTotalCbs") BigDecimal totalCbsValue,

    @Schema(description = "Itens da nota fiscal")
    @JsonProperty("itens") List<InvoiceItemResponse> items,

    @Schema(description = "Data e hora de criacao da nota")
    OffsetDateTime createdAt,

    @Schema(description = "Data e hora da ultima atualizacao da nota")
    OffsetDateTime updatedAt,

    @Schema(
        description = "Tipo do documento fiscal: NFE (padrao) ou NFA (Nota Fiscal Avulsa "
            + "do produtor rural pessoa fisica)",
        example = "NFE")
    @JsonProperty("tipoDocumento") DocumentType documentType,

    @Schema(description = "Data/hora do cancelamento (nula se nao cancelada)")
    @JsonProperty("dataCancelamento") OffsetDateTime cancellationDate,

    @Schema(description = "Motivo do cancelamento (nulo se nao cancelada)")
    @JsonProperty("motivoCancelamento") String cancellationReason
) {

  /**
   * Endereco do destinatario retornado na resposta da invoice fiscal.
   */
  @Schema(description = "Endereco do destinatario da nota fiscal")
  public record RecipientAddressResponse(

      @Schema(description = "Logradouro", example = "Rua das Flores")
      @JsonProperty("logradouro") String street,

      @Schema(description = "Numero do imovel", example = "100")
      @JsonProperty("numero") String number,

      @Schema(description = "Nome do municipio", example = "Cuiaba")
      @JsonProperty("municipio") String city,

      @Schema(description = "Codigo IBGE do municipio", example = "5103403")
      @JsonProperty("codigoMunicipioIbge") String ibgeCityCode,

      @Schema(description = "Sigla da UF", example = "MT")
      String uf,

      @Schema(description = "CEP sem formatacao", example = "78000000")
      @JsonProperty("cep") String zipCode
  ) {}
}
