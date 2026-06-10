package br.com.agrocontabil.dto;

import br.com.agrocontabil.model.StatusNotaFiscal;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * DTO de saida para uma nota fiscal.
 *
 * <p>Expoe todos os campos relevantes da entidade {@link br.com.agrocontabil.model.NotaFiscal},
 * incluindo os valores calculados, status, itens e timestamps de auditoria.
 * A entidade JPA nunca e exposta diretamente — sempre via este record.
 */
@Schema(description = "Dados completos de uma nota fiscal eletronica (NF-e modelo 55)")
public record NotaFiscalResponse(

    @Schema(description = "Identificador unico da nota fiscal",
        example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
    UUID id,

    @Schema(description = "Chave de acesso NF-e (44 digitos; nula quando em RASCUNHO)",
        example = "35260612345678000195550010000000011234567890")
    String chaveAcesso,

    @Schema(description = "Numero sequencial da NF-e", example = "1")
    Long numero,

    @Schema(description = "Serie da NF-e", example = "1")
    Integer serie,

    @Schema(description = "Modelo do documento fiscal", example = "55")
    String modelo,

    @Schema(description = "Natureza da operacao", example = "Venda de producao rural")
    String naturezaOperacao,

    @Schema(description = "Status do ciclo de vida da nota", example = "RASCUNHO")
    StatusNotaFiscal status,

    @Schema(description = "Data e hora de emissao", example = "2026-06-10T10:00:00-03:00")
    OffsetDateTime dataEmissao,

    @Schema(description = "Data e hora de saida/entrega (pode ser nula)",
        example = "2026-06-10T14:00:00-03:00")
    OffsetDateTime dataSaida,

    @Schema(description = "Identificador do produtor emissor",
        example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
    UUID produtorId,

    @Schema(description = "Nome ou razao social do destinatario", example = "Jose Comprador")
    String destinatarioNome,

    @Schema(description = "CPF ou CNPJ do destinatario (somente digitos)", example = "12345678901")
    String destinatarioCpfCnpj,

    @Schema(description = "Inscricao Estadual do destinatario (pode ser nula)")
    String destinatarioIe,

    @Schema(description = "Endereco do destinatario (pode ser nulo)")
    EnderecoDestinatarioResponse destinatarioEndereco,

    @Schema(description = "Somatório dos valores dos produtos (sem impostos)", example = "1250.00")
    BigDecimal valorTotalProdutos,

    @Schema(description = "Valor total da nota fiscal", example = "1250.00")
    BigDecimal valorTotalNota,

    @Schema(description = "Itens da nota fiscal")
    List<NotaFiscalItemResponse> itens,

    @Schema(description = "Data e hora de criacao da nota")
    OffsetDateTime createdAt,

    @Schema(description = "Data e hora da ultima atualizacao da nota")
    OffsetDateTime updatedAt
) {

  /**
   * Endereco do destinatario retornado na resposta da nota fiscal.
   */
  @Schema(description = "Endereco do destinatario da nota fiscal")
  public record EnderecoDestinatarioResponse(

      @Schema(description = "Logradouro", example = "Rua das Flores")
      String logradouro,

      @Schema(description = "Numero do imovel", example = "100")
      String numero,

      @Schema(description = "Nome do municipio", example = "Cuiaba")
      String municipio,

      @Schema(description = "Codigo IBGE do municipio", example = "5103403")
      String codigoMunicipioIbge,

      @Schema(description = "Sigla da UF", example = "MT")
      String uf,

      @Schema(description = "CEP sem formatacao", example = "78000000")
      String cep
  ) {}
}
