package br.com.agrocontabil.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import br.com.agrocontabil.model.InvoiceStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/**
 * DTO de saída do envio de uma NF-e à SEFAZ.
 *
 * <p>Reflete o result da transmissão: status resultante da invoice e os dados do retorno
 * do fisco (código, reason, protocol e data de autorização). Em rejeição/pendência o
 * protocol e a data de autorização são nulos.
 *
 * @param accessKey     key de acesso da NF-e (44 dígitos)
 * @param status          status resultante da invoice após o envio
 * @param cStat           código de status retornado pela SEFAZ (ex.: "100")
 * @param xMotivo         descrição textual do status retornado
 * @param protocol       número do protocol de autorização (nulo se não autorizada)
 * @param authorizationDate instante da autorização pelo fisco (nulo se não autorizada)
 */
@Schema(description = "Resultado do envio de uma NF-e a SEFAZ")
public record SefazSubmissionResponse(

    @Schema(description = "Chave de acesso da NF-e (44 digitos)",
        example = "41262612345678000195550010000000011000000012")
    @JsonProperty("chaveAcesso") String accessKey,

    @Schema(description = "Status resultante da nota apos o envio", example = "AUTORIZADA")
    InvoiceStatus status,

    @Schema(description = "Codigo de status retornado pela SEFAZ", example = "100")
    String cStat,

    @Schema(description = "Descricao textual do status", example = "Autorizado o uso da NF-e")
    String xMotivo,

    @Schema(description = "Numero do protocolo de autorizacao (nulo se nao autorizada)",
        example = "141260000000017")
    @JsonProperty("protocolo") String protocol,

    @Schema(description = "Data e hora da autorizacao pelo fisco (nula se nao autorizada)",
        example = "2026-06-15T10:00:00-03:00")
    OffsetDateTime authorizationDate) {}
