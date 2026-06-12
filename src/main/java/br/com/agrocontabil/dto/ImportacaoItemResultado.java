package br.com.agrocontabil.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * Resultado da importação de um único arquivo XML.
 */
@Schema(description = "Resultado da importacao de um arquivo XML")
public record ImportacaoItemResultado(

    @Schema(description = "Nome do arquivo enviado", example = "nota1.xml")
    String arquivo,

    @Schema(description = "Indica se o arquivo foi importado com sucesso", example = "true")
    boolean sucesso,

    @Schema(description = "Id da nota criada (quando sucesso)")
    UUID notaId,

    @Schema(description = "Chave de acesso da nota (quando sucesso)")
    String chaveAcesso,

    @Schema(description = "Motivo do erro (quando falha)")
    String erro
) {

  /** Cria um resultado de sucesso. */
  public static ImportacaoItemResultado sucesso(String arquivo, UUID notaId, String chave) {
    return new ImportacaoItemResultado(arquivo, true, notaId, chave, null);
  }

  /** Cria um resultado de erro. */
  public static ImportacaoItemResultado erro(String arquivo, String motivo) {
    return new ImportacaoItemResultado(arquivo, false, null, null, motivo);
  }
}
