package br.com.agrocontabil.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Relatório consolidado da importação de um lote de files XML.
 */
@Schema(description = "Relatorio de importacao de um lote de XMLs")
public record ImportBatchResponse(

    @Schema(description = "Total de arquivos recebidos", example = "3")
    int total,

    @Schema(description = "Quantidade importada com sucesso", example = "2")
    @JsonProperty("importados") int imported,

    @Schema(description = "Quantidade que falhou", example = "1")
    @JsonProperty("falhas") int failures,

    @Schema(description = "Resultado por arquivo")
    @JsonProperty("resultados") List<ImportItemResult> results
) {

  /** Monta o relatório a partir da lista de results por fileName. */
  public static ImportBatchResponse de(List<ImportItemResult> results) {
    int ok = (int) results.stream().filter(ImportItemResult::success).count();
    return new ImportBatchResponse(results.size(), ok, results.size() - ok, results);
  }
}
