package br.com.agrocontabil.dto;

import br.com.agrocontabil.model.TipoDocumentoFiscal;
import br.com.agrocontabil.validation.CodigoIbgeCoerenteComUf;
import br.com.agrocontabil.validation.EnderecoComCodigoIbge;
import br.com.agrocontabil.validation.ValidCpfCnpj;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * DTO de entrada para criacao e atualizacao de nota fiscal.
 *
 * <p>Campos calculados ({@code valorTotalProdutos}, {@code valorTotalNota}) NAO sao aceitos
 * do cliente — sao computados no mapper para evitar mass-assignment.
 * O campo {@code modelo} e fixado como {@code "55"} (NF-e) pelo mapper.
 *
 * <p>O record aninhado {@link EnderecoDestinatarioRequest} representa o endereco do
 * destinatario, com todos os campos opcionais.
 */
@Schema(description = "Dados para emissao de uma nota fiscal eletronica (NF-e modelo 55)")
public record NotaFiscalRequest(

    @Schema(description = "Numero sequencial da NF-e (1 a 999.999.999)", example = "1")
    @NotNull(message = "número é obrigatório")
    @Positive(message = "número deve ser positivo")
    @Max(value = 999_999_999, message = "número deve ser no máximo 999999999 (layout da NF-e)")
    Long numero,

    @Schema(description = "Serie da NF-e, 1 a 999 (padrao 1 quando omitido)", example = "1")
    @Positive(message = "série deve ser positiva")
    @Max(value = 999, message = "série deve ser no máximo 999 (layout da NF-e)")
    Integer serie,

    @Schema(description = "Natureza da operacao", example = "Venda de producao rural")
    @NotBlank(message = "naturezaOperacao é obrigatória")
    @Size(max = 255)
    String naturezaOperacao,

    @Schema(description = "Data e hora de emissao da nota", example = "2026-06-10T10:00:00-03:00")
    @NotNull(message = "dataEmissao é obrigatória")
    OffsetDateTime dataEmissao,

    @Schema(description = "Data e hora de saida/entrega (opcional)",
        example = "2026-06-10T14:00:00-03:00")
    OffsetDateTime dataSaida,

    @Schema(
        description = "Tipo do documento fiscal: NFE (padrao) ou NFA (Nota Fiscal Avulsa do "
            + "produtor rural pessoa fisica). Quando omitido, assume NFE.",
        example = "NFE")
    TipoDocumentoFiscal tipoDocumento,

    @Schema(description = "Nome ou razao social do destinatario", example = "Jose Comprador")
    @NotBlank(message = "destinatarioNome é obrigatório")
    @Size(max = 255)
    String destinatarioNome,

    @Schema(
        description = "CPF (11 digitos) ou CNPJ (14 digitos) do destinatario, apenas numeros",
        example = "12345678901")
    @NotBlank(message = "destinatarioCpfCnpj é obrigatório")
    @Pattern(
        regexp = "\\d{11}|\\d{14}",
        message = "destinatarioCpfCnpj deve ter 11 dígitos (CPF) ou 14 dígitos (CNPJ)")
    @ValidCpfCnpj
    String destinatarioCpfCnpj,

    @Schema(description = "Inscricao Estadual do destinatario (opcional)", example = "123456789")
    @Size(max = 20)
    String destinatarioIe,

    @Schema(description = "Endereco do destinatario (opcional)")
    @Valid
    EnderecoDestinatarioRequest destinatarioEndereco,

    @Schema(description = "Lista de itens da nota fiscal (ao menos 1 item obrigatorio)")
    @NotEmpty(message = "itens não pode ser vazio")
    @Size(max = 990, message = "A nota fiscal pode ter no máximo 990 itens")
    @Valid
    List<NotaFiscalItemRequest> itens
) {

  /**
   * Endereco do destinatario da nota fiscal.
   *
   * <p>Todos os campos sao opcionais; quando presentes, sao validados por formato.
   */
  @Schema(description = "Endereco do destinatario da nota fiscal")
  @CodigoIbgeCoerenteComUf
  public record EnderecoDestinatarioRequest(

      @Schema(description = "Logradouro (rua, avenida etc.)", example = "Rua das Flores")
      @Size(max = 255, message = "logradouro deve ter no máximo 255 caracteres")
      String logradouro,

      @Schema(description = "Numero do imovel", example = "100")
      @Size(max = 20, message = "número deve ter no máximo 20 caracteres")
      String numero,

      @Schema(description = "Nome do municipio", example = "Cuiaba")
      @Size(max = 255, message = "município deve ter no máximo 255 caracteres")
      String municipio,

      @Schema(description = "Codigo IBGE do municipio (7 digitos)", example = "5103403")
      @Pattern(
          regexp = "\\d{7}",
          message = "codigoMunicipioIbge deve ter exatamente 7 dígitos numéricos")
      String codigoMunicipioIbge,

      @Schema(description = "Sigla da UF (2 letras maiusculas)", example = "MT")
      @Pattern(regexp = "[A-Z]{2}", message = "uf deve ter exatamente 2 letras maiusculas")
      String uf,

      @Schema(description = "CEP sem formatacao (8 digitos)", example = "78000000")
      @Pattern(regexp = "\\d{8}", message = "cep deve ter exatamente 8 dígitos numéricos")
      String cep
  ) implements EnderecoComCodigoIbge {}
}
