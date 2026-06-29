package br.com.agrocontabil.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import br.com.agrocontabil.model.DocumentType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * DTO de entrada para criacao e atualizacao de invoice fiscal.
 *
 * <p>Campos calculados ({@code totalProductsValue}, {@code totalInvoiceValue}) NAO sao aceitos
 * do cliente — sao computados no mapper para evitar mass-assignment.
 * O field {@code model} e fixado como {@code "55"} (NF-e) pelo mapper.
 *
 * <p>O record aninhado {@link RecipientAddressRequest} representa o endereco do
 * destinatario, com todos os campos opcionais.
 */
@Schema(description = "Dados para emissao de uma nota fiscal eletronica (NF-e modelo 55)")
public record InvoiceRequest(

    @Schema(description = "Numero sequencial da NF-e", example = "1")
    @NotNull(message = "numero e obrigatorio")
    @Positive(message = "numero deve ser positivo")
    @JsonProperty("numero") Long number,

    @Schema(description = "Serie da NF-e (padrao 1 quando omitido)", example = "1")
    @Positive(message = "serie deve ser positiva")
    @JsonProperty("serie") Integer series,

    @Schema(description = "Natureza da operacao", example = "Venda de producao rural")
    @NotBlank(message = "naturezaOperacao e obrigatoria")
    @Size(max = 255)
    @JsonProperty("naturezaOperacao") String operationNature,

    @Schema(description = "Data e hora de emissao da nota", example = "2026-06-10T10:00:00-03:00")
    @NotNull(message = "dataEmissao e obrigatoria")
    @JsonProperty("dataEmissao") OffsetDateTime issuanceDate,

    @Schema(description = "Data e hora de saida/entrega (opcional)",
        example = "2026-06-10T14:00:00-03:00")
    @JsonProperty("dataSaida") OffsetDateTime exitDate,

    @Schema(description = "Nome ou razao social do destinatario", example = "Jose Comprador")
    @NotBlank(message = "destinatarioNome e obrigatorio")
    @Size(max = 255)
    @JsonProperty("destinatarioNome") String recipientName,

    @Schema(
        description = "CPF (11 digitos) ou CNPJ (14 digitos) do destinatario, apenas numeros",
        example = "12345678901")
    @NotBlank(message = "destinatarioCpfCnpj e obrigatorio")
    @Pattern(
        regexp = "\\d{11}|\\d{14}",
        message = "destinatarioCpfCnpj deve ter 11 digitos (CPF) ou 14 digitos (CNPJ)")
    @JsonProperty("destinatarioCpfCnpj") String recipientTaxId,

    @Schema(description = "Inscricao Estadual do destinatario (opcional)", example = "123456789")
    @Size(max = 20)
    @JsonProperty("destinatarioIe") String recipientStateRegistration,

    @Schema(description = "Endereco do destinatario (opcional)")
    @Valid
    @JsonProperty("destinatarioEndereco") RecipientAddressRequest recipientAddress,

    @Schema(description = "Lista de itens da nota fiscal (ao menos 1 item obrigatorio)")
    @NotEmpty(message = "itens nao pode ser vazio")
    @Size(max = 990, message = "A nota fiscal pode ter no maximo 990 itens")
    @Valid
    @JsonProperty("itens") List<InvoiceItemRequest> items,

    @Schema(
        description = "Tipo do documento: NFE (padrao) ou NFA (Nota Fiscal Avulsa "
            + "do produtor rural pessoa fisica)",
        example = "NFE")
    @JsonProperty("tipoDocumento") DocumentType documentType
) {

  /**
   * Endereco do destinatario da invoice fiscal.
   *
   * <p>Todos os campos sao opcionais; quando presentes, sao validados por formato.
   */
  @Schema(description = "Endereco do destinatario da nota fiscal")
  public record RecipientAddressRequest(

      @Schema(description = "Logradouro (rua, avenida etc.)", example = "Rua das Flores")
      @Size(max = 255, message = "logradouro deve ter no maximo 255 caracteres")
      @JsonProperty("logradouro") String street,

      @Schema(description = "Numero do imovel", example = "100")
      @Size(max = 20, message = "numero deve ter no maximo 20 caracteres")
      @JsonProperty("numero") String number,

      @Schema(description = "Nome do municipio", example = "Cuiaba")
      @Size(max = 255, message = "municipio deve ter no maximo 255 caracteres")
      @JsonProperty("municipio") String city,

      @Schema(description = "Codigo IBGE do municipio (7 digitos)", example = "5103403")
      @Pattern(
          regexp = "\\d{7}",
          message = "codigoMunicipioIbge deve ter exatamente 7 digitos numericos")
      @JsonProperty("codigoMunicipioIbge") String ibgeCityCode,

      @Schema(description = "Sigla da UF (2 letras maiusculas)", example = "MT")
      @Pattern(regexp = "[A-Z]{2}", message = "uf deve ter exatamente 2 letras maiusculas")
      String uf,

      @Schema(description = "CEP sem formatacao (8 digitos)", example = "78000000")
      @Pattern(regexp = "\\d{8}", message = "cep deve ter exatamente 8 digitos numericos")
      @JsonProperty("cep") String zipCode
  ) {}
}
