package br.com.agrocontabil.dto;

import br.com.agrocontabil.model.StatusNotaFiscal;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/**
 * DTO de saída do envio de uma NF-e à SEFAZ.
 *
 * <p>Reflete o resultado da transmissão: status resultante da nota e os dados do retorno
 * do fisco (código, motivo, protocolo e data de autorização). Em rejeição/pendência o
 * protocolo e a data de autorização são nulos.
 *
 * @param chaveAcesso     chave de acesso da NF-e (44 dígitos)
 * @param status          status resultante da nota após o envio
 * @param cStat           código de status retornado pela SEFAZ (ex.: "100")
 * @param xMotivo         descrição textual do status retornado
 * @param protocolo       número do protocolo de autorização (nulo se não autorizada)
 * @param dataAutorizacao instante da autorização pelo fisco (nulo se não autorizada)
 */
@Schema(description = "Resultado do envio de uma NF-e a SEFAZ")
public record SefazEnvioResponse(

    @Schema(description = "Chave de acesso da NF-e (44 digitos)",
        example = "41262612345678000195550010000000011000000012")
    String chaveAcesso,

    @Schema(description = "Status resultante da nota apos o envio", example = "AUTORIZADA")
    StatusNotaFiscal status,

    @Schema(description = "Codigo de status retornado pela SEFAZ", example = "100")
    String cStat,

    @Schema(description = "Descricao textual do status", example = "Autorizado o uso da NF-e")
    String xMotivo,

    @Schema(description = "Numero do protocolo de autorizacao (nulo se nao autorizada)",
        example = "141260000000017")
    String protocolo,

    @Schema(description = "Data e hora da autorizacao pelo fisco (nula se nao autorizada)",
        example = "2026-06-15T10:00:00-03:00")
    OffsetDateTime dataAutorizacao) implements ResultadoAuditavel {}
