package br.com.agrocontabil.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * Resultado da importação de um único fileName XML.
 */
@Schema(description = "Resultado da importacao de um arquivo XML")
public record ImportItemResult(

    @Schema(description = "Nome do arquivo enviado", example = "nota1.xml")
    @JsonProperty("arquivo") String fileName,

    @Schema(description = "Indica se o arquivo foi importado com sucesso", example = "true")
    @JsonProperty("sucesso") boolean success,

    @Schema(description = "Id da nota criada (quando sucesso)")
    @JsonProperty("notaId") UUID invoiceId,

    @Schema(description = "Chave de acesso da nota (quando sucesso)")
    @JsonProperty("chaveAcesso") String accessKey,

    @Schema(description = "Motivo do erro (quando falha)")
    @JsonProperty("erro") String error
) {

  /** Cria um result de success. */
  public static ImportItemResult success(String fileName, UUID invoiceId, String key) {
    return new ImportItemResult(fileName, true, invoiceId, key, null);
  }

  /** Cria um result de error. */
  public static ImportItemResult error(String fileName, String reason) {
    return new ImportItemResult(fileName, false, null, null, reason);
  }
}
