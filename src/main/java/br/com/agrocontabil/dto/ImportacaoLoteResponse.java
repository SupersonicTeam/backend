package br.com.agrocontabil.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Relatório consolidado da importação de um lote de arquivos XML.
 */
@Schema(description = "Relatorio de importacao de um lote de XMLs")
public record ImportacaoLoteResponse(

    @Schema(description = "Total de arquivos recebidos", example = "3")
    int total,

    @Schema(description = "Quantidade importada com sucesso", example = "2")
    int importados,

    @Schema(description = "Quantidade que falhou", example = "1")
    int falhas,

    @Schema(description = "Resultado por arquivo")
    List<ImportacaoItemResultado> resultados
) {

  /** Monta o relatório a partir da lista de resultados por arquivo. */
  public static ImportacaoLoteResponse de(List<ImportacaoItemResultado> resultados) {
    int ok = (int) resultados.stream().filter(ImportacaoItemResultado::sucesso).count();
    return new ImportacaoLoteResponse(resultados.size(), ok, resultados.size() - ok, resultados);
  }
}
