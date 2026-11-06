package br.com.agrocontabil.dto;

import br.com.agrocontabil.model.OrigemNotaFiscal;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.model.TipoDocumentoFiscal;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * DTO de saída para uma nota fiscal.
 *
 * <p>Expõe todos os campos relevantes da entidade {@link br.com.agrocontabil.model.NotaFiscal},
 * incluindo os valores calculados, status, itens e timestamps de auditoria.
 * A entidade JPA nunca é exposta diretamente — sempre via este record.
 *
 * <p>Os campos {@code valorTotalIbs} e {@code valorTotalCbs} são nulos para notas emitidas
 * antes da Sprint 2 – Sessão 4 (IBS/CBS). Base legal: EC 132/2023 + LC 214/2025.
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

    @Schema(description = "Tipo do documento fiscal: NFE ou NFA", example = "NFE")
    TipoDocumentoFiscal tipoDocumento,

    @Schema(description = "Origem da nota: PROPRIA (emitida pelo produtor) ou IMPORTADA (compra, "
        + "emitida por um fornecedor; o destinatario e o proprio produtor e quem cancela e o "
        + "fornecedor)", example = "PROPRIA")
    OrigemNotaFiscal origem,

    @Schema(description = "Nome ou razao social do fornecedor que emitiu a nota. So em nota "
        + "IMPORTADA; nulo na propria, cujo emitente e o produtor",
        example = "Posto Rodoviario Exemplo Ltda", nullable = true)
    String emitenteNome,

    @Schema(description = "CPF ou CNPJ do fornecedor que emitiu a nota (somente digitos). So em "
        + "nota IMPORTADA; nulo na propria", example = "12345678000195", nullable = true)
    String emitenteCpfCnpj,

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

    @Schema(
        description =
            "Somatório do IBS calculado de todos os itens. "
                + "Informativo em 2026 (ano-teste, LC 214/2025). "
                + "Nulo para notas anteriores à Sprint 2 Sessão 4.",
        example = "1.25")
    BigDecimal valorTotalIbs,

    @Schema(
        description =
            "Somatório da CBS calculada de todos os itens. "
                + "Informativo em 2026 (ano-teste, LC 214/2025). "
                + "Nulo para notas anteriores à Sprint 2 Sessão 4.",
        example = "11.25")
    BigDecimal valorTotalCbs,

    @Schema(description = "Itens da nota fiscal")
    List<NotaFiscalItemResponse> itens,

    @Schema(description = "Data e hora de criacao da nota")
    OffsetDateTime createdAt,

    @Schema(description = "Data e hora da ultima atualizacao da nota")
    OffsetDateTime updatedAt,

    @Schema(description = "Avisos nao bloqueantes da gravacao (criar e atualizar o rascunho): "
        + "a nota foi salva, mas o imposto mostrado pode nao valer na emissao. Vazio nas demais "
        + "respostas.")
    List<AvisoNotaResponse> avisos
) implements ResultadoAuditavel {

  @Override
  public UUID recursoAuditado() {
    return id;
  }

  @Override
  public UUID produtorAuditado() {
    return produtorId;
  }

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
